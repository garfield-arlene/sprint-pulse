param([string]$Url, [string]$OutFile)
$ErrorActionPreference = 'Stop'
$targets = Invoke-RestMethod -Uri 'http://127.0.0.1:9222/json/list'
$page = @($targets | Where-Object { $_.type -eq 'page' -and $_.webSocketDebuggerUrl })[0]
$ws = New-Object System.Net.WebSockets.ClientWebSocket
$ws.ConnectAsync([Uri]$page.webSocketDebuggerUrl, [System.Threading.CancellationToken]::None).Wait()
$script:nextId = 0
function Invoke-Cdp([string]$method, $params) {
  $script:nextId++; $id = $script:nextId
  $msg = @{ id = $id; method = $method; params = $params } | ConvertTo-Json -Compress -Depth 20
  $bytes = [System.Text.Encoding]::UTF8.GetBytes($msg)
  $ws.SendAsync([System.ArraySegment[byte]]::new($bytes), [System.Net.WebSockets.WebSocketMessageType]::Text, $true, [System.Threading.CancellationToken]::None).Wait()
  while ($true) {
    $buf = New-Object byte[] 1048576
    $sb = New-Object System.Text.StringBuilder
    do {
      $res = $ws.ReceiveAsync([System.ArraySegment[byte]]::new($buf), [System.Threading.CancellationToken]::None).Result
      [void]$sb.Append([System.Text.Encoding]::UTF8.GetString($buf, 0, $res.Count))
    } while (-not $res.EndOfMessage)
    $obj = $sb.ToString() | ConvertFrom-Json
    if ($null -ne $obj.id -and [int]$obj.id -eq $id) { return $obj }
  }
}
function Eval-Js([string]$expr) {
  $r = Invoke-Cdp 'Runtime.evaluate' @{ expression = $expr; returnByValue = $true; awaitPromise = $true }
  if ($r.result.exceptionDetails) { return 'EXC: ' + ($r.result.exceptionDetails.text) }
  return $r.result.result.value
}
Invoke-Cdp 'Page.enable' @{} | Out-Null
Invoke-Cdp 'Runtime.enable' @{} | Out-Null
Invoke-Cdp 'Page.navigate' @{ url = $Url } | Out-Null
$deadline = (Get-Date).AddSeconds(90)
while ((Get-Date) -lt $deadline) {
  Start-Sleep -Milliseconds 1500
  if ((Eval-Js "document.querySelector('iframe') ? 'yes' : 'no'") -eq 'yes') { break }
}
Start-Sleep -Seconds 4
$sel = "(function(){var f=document.querySelector('iframe');var d=f.contentDocument;var s=d.getElementById('sprintSelect');if(!s)return 'no-select';var m=Array.prototype.filter.call(s.options,function(o){return o.value==='1';})[0];if(m&&s.value!=='1'){s.value='1';s.dispatchEvent(new Event('change',{bubbles:true}));}return 'selected='+s.value;})()"
Write-Host (Eval-Js $sel)
$cardExpr = "(function(){var d=document.querySelector('iframe').contentDocument;var cs=d.querySelectorAll('.health-card');return Array.prototype.map.call(cs,function(c){return c.innerText.replace(/\s+/g,' ').trim();}).join(' | ');})()"
$deadline = (Get-Date).AddSeconds(60)
$cards = ''
while ((Get-Date) -lt $deadline) {
  $cards = Eval-Js $cardExpr
  if ($cards -like '*Flagged*' -and $cards -like '*Blocked*') { break }
  Start-Sleep -Milliseconds 1500
}
Write-Host ("CARDS: " + $cards)
$shot = Invoke-Cdp 'Page.captureScreenshot' @{ format = 'png'; captureBeyondViewport = $true }
[System.IO.File]::WriteAllBytes($OutFile, [System.Convert]::FromBase64String($shot.result.data))
Write-Host 'screenshot saved'
