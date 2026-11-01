# Aduce capturile de ecran la raportul maxim acceptat de Google Play (2:1), cu benzi negre sus și jos.
# Rulare: powershell -ExecutionPolicy Bypass -File play-store\make-screenshots.ps1 -Source <folder cu capturi>
param([Parameter(Mandatory = $true)][string]$Source)
Add-Type -AssemblyName System.Drawing

$out = Join-Path (Split-Path -Parent $MyInvocation.MyCommand.Path) 'screenshots'
New-Item -ItemType Directory -Force $out | Out-Null

$i = 1
Get-ChildItem $Source -File | Where-Object { $_.Extension -match '\.(png|jpe?g)$' } | Sort-Object Name | ForEach-Object {
    $img = [System.Drawing.Image]::FromFile($_.FullName)
    try {
        # Doar capturile în landscape mai late de 2:1 trebuie completate
        if ($img.Width -le $img.Height) { return }
        $w = $img.Width
        $h = [Math]::Max($img.Height, [int][Math]::Ceiling($w / 2))
        $bmp = New-Object System.Drawing.Bitmap $w, $h
        $g = [System.Drawing.Graphics]::FromImage($bmp)
        $g.Clear([System.Drawing.Color]::Black)
        $g.DrawImage($img, 0, [int](($h - $img.Height) / 2), $img.Width, $img.Height)
        $name = 'screenshot-{0}.png' -f $i
        $bmp.Save((Join-Path $out $name), [System.Drawing.Imaging.ImageFormat]::Png)
        Write-Output ('{0} <- {1} ({2}x{3})' -f $name, $_.Name, $w, $h)
        $g.Dispose(); $bmp.Dispose()
        $script:i++
    } finally {
        $img.Dispose()
    }
}
