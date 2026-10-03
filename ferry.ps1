# ferry.ps1 - sync clipboard text between this PC and an Android phone through ntfy.sh.
# Phone side: the Ferry app, set to the same topic.
# The topic (in topic.txt) is the only secret: anyone who knows it can read and send to your clipboard.
param(
  [string]$Topic  = (Get-Content "$PSScriptRoot\topic.txt" -ErrorAction Stop).Trim(),
  [string]$Server = 'https://ntfy.sh'
)

Add-Type -AssemblyName System.Windows.Forms
$http = [System.Net.Http.HttpClient]::new()
$http.Timeout = [System.Threading.Timeout]::InfiniteTimeSpan

function Read-Clip { try { Get-Clipboard -Raw } catch { $script:last } }   # clipboard can be locked by another app

# Password managers mark their copies with these formats. Never send those.
function Test-Private {
  [System.Windows.Forms.Clipboard]::ContainsData('ExcludeClipboardContentFromMonitorProcessing') -or
  [System.Windows.Forms.Clipboard]::ContainsData('Clipboard Viewer Ignore')
}

function Send-Clip([string]$text) {
  # Cache: no = ntfy.sh only passes the message on, it does not store it. Priority 2 = silent.
  $headers = @{ Cache = 'no'; Title = 'From laptop'; Tags = 'laptop'; Priority = '2' }
  $bytes = [Text.Encoding]::UTF8.GetBytes($text)
  if ($bytes.Length -gt 4000) {
    # Too big for a Copy button (ntfy.sh rejects it), so send it as a text file the phone can open.
    Invoke-RestMethod -Method Put -Uri "$Server/$Topic" -Headers ($headers + @{ Filename = 'clipboard.txt' }) -Body $bytes | Out-Null
    return
  }
  $preview = if ($text.Length -gt 100) { $text.Substring(0, 100) + '...' } else { $text }
  $body = @{ topic = $Topic; message = $preview; actions = @(@{ action = 'copy'; label = 'Copy'; value = $text }) } |
    ConvertTo-Json -Depth 4 -Compress
  Invoke-RestMethod -Method Post -Uri $Server -Headers $headers -ContentType 'application/json' `
    -Body ([Text.Encoding]::UTF8.GetBytes($body)) | Out-Null
}

$last  = Read-Clip
$since = $null   # id of the last message seen, so a reconnect catches up on what it missed

while ($true) {
  $reader = $null
  try {
    $url = "$Server/$Topic/json" + $(if ($since) { "?since=$since" })
    $reader = [IO.StreamReader]::new($http.GetStreamAsync($url).GetAwaiter().GetResult())
    $read = $reader.ReadLineAsync()
    $heard = Get-Date
    Write-Host "Connected to $Topic"

    while ($true) {
      if ($read.Wait(500)) {
        $line = $read.Result
        if ($null -eq $line) { break }   # server closed the stream
        $heard = Get-Date
        $m = $line | ConvertFrom-Json
        if ($m.event -eq 'message') {
          $since = $m.id
          if ($m.tags -notcontains 'laptop') {
            # ntfy.sh turns long text into a file attachment. Skip non-text files such as photos.
            $text = if (-not $m.attachment) { $m.message }
                    elseif ($m.attachment.type -like 'text/*' -and $m.attachment.url.StartsWith("$Server/")) { $http.GetStringAsync($m.attachment.url).GetAwaiter().GetResult() }
            if ($text) {
              Set-Clipboard -Value $text
              $last = Read-Clip   # read back, so line-ending changes don't echo it to the phone
            }
          }
        }
        $read = $reader.ReadLineAsync()
      }
      elseif (((Get-Date) - $heard).TotalSeconds -gt 90) { break }   # ntfy sends a keepalive every 45 s; silence = dead link (e.g. after sleep)

      $now = Read-Clip
      if ($now -and $now -ne $last) {
        if (-not (Test-Private)) { Send-Clip $now }
        $last = $now   # set after sending: if the send fails, the next connection retries it
      }
    }
  }
  catch { Write-Warning $_.Exception.Message; Start-Sleep 5 }
  finally { if ($reader) { $reader.Dispose() } }
}
