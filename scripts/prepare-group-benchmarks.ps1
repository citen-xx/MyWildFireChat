param(
    [string] $BaseUrl = "http://localhost:8080",
    [string] $Username = "alice",
    [string] $Password = "password123",
    [string] $Output = "benchmark-results\group-benchmarks.json"
)

$ErrorActionPreference = "Stop"
$login = Invoke-RestMethod `
    -Uri "$BaseUrl/api/auth/login" `
    -Method Post `
    -ContentType "application/json" `
    -Body (@{ username = $Username; password = $Password } | ConvertTo-Json)
$headers = @{ Authorization = "Bearer $($login.token)" }

$groups = foreach ($size in @(5, 20, 50, 100)) {
    $memberIds = @(2001..(2000 + $size - 1))
    $response = Invoke-RestMethod `
        -Uri "$BaseUrl/api/groups" `
        -Method Post `
        -Headers $headers `
        -ContentType "application/json" `
        -Body (@{
            name = "phase10-fanout-$size"
            memberIds = $memberIds
        } | ConvertTo-Json)
    [ordered]@{
        size = $size
        groupId = $response.groupId
        conversationId = $response.conversationId
        receiverUsernames = ($memberIds | ForEach-Object { "load{0:D3}" -f ($_ - 2000) }) -join ","
    }
}

New-Item -ItemType Directory -Force -Path (Split-Path $Output) | Out-Null
$groups | ConvertTo-Json -Depth 4 | Set-Content -Encoding utf8 $Output
$groups | Format-Table | Out-Host
