$src = 'c:\Users\bratt\SynologyDrive\Privat\Frekvensplan sverige\Sweden.json'
$dst = (Resolve-Path .).Path + '\src\hackrf-sweep\freq\Sweden.csv'

$raw  = [System.IO.File]::ReadAllText($src, [System.Text.UTF8Encoding]::new($false))
$json = $raw | ConvertFrom-Json

$inv = [System.Globalization.CultureInfo]::InvariantCulture
$country = 'Sweden (PTS)'
$q = [char]34
$lines = New-Object System.Collections.Generic.List[string]
[void]$lines.Add('Country;Frequency Range;Allocations;Applications')

foreach ($b in $json.bands) {
    $startMHz = [double]($b.start) / 1000000.0
    $endMHz   = [double]($b.end)   / 1000000.0
    $startStr = $startMHz.ToString('0.######', $inv)
    $endStr   = $endMHz.ToString('0.######', $inv)
    # JSON 'type' is AARRGGBB; drop alpha, prefix '#'
    $rgb = '#' + $b.type.Substring(2)
    $name = ($b.name -replace '"', "'")
    $line = $q + $country + $q + ';' + $q + $startStr + ' - ' + $endStr + ' MHz' + $q + ';' + $q + $name + $q + ';' + $q + $rgb + $q
    [void]$lines.Add($line)
}

[System.IO.File]::WriteAllLines($dst, $lines, [System.Text.UTF8Encoding]::new($false))
Write-Host ('Wrote ' + $lines.Count + ' lines to ' + $dst)
