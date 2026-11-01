# Generează iconița de 512x512 și imaginea de prezentare 1024x500 pentru Google Play.
# Rulare: powershell -ExecutionPolicy Bypass -File play-store\make-graphics.ps1
Add-Type -AssemblyName System.Drawing

$out = Split-Path -Parent $MyInvocation.MyCommand.Path

function New-Color([string]$hex) { [System.Drawing.ColorTranslator]::FromHtml($hex) }

function Add-RoundRect($path, [float]$x, [float]$y, [float]$w, [float]$h, [float]$r) {
    $d = 2 * $r
    $path.AddArc($x, $y, $d, $d, 180, 90)
    $path.AddArc($x + $w - $d, $y, $d, $d, 270, 90)
    $path.AddArc($x + $w - $d, $y + $h - $d, $d, $d, 0, 90)
    $path.AddArc($x, $y + $h - $d, $d, $d, 90, 90)
    $path.CloseFigure()
}

# Același robot ca iconița din aplicație (coordonate în grila de 108), scalat și mutat
function Draw-Robot($g, [float]$s, [float]$ox, [float]$oy) {
    $white = New-Object System.Drawing.SolidBrush (New-Color '#FFFFFF')
    $amber = New-Object System.Drawing.SolidBrush (New-Color '#FFC107')
    $grey = New-Object System.Drawing.SolidBrush (New-Color '#B0BEC5')
    $navy = New-Object System.Drawing.SolidBrush (New-Color '#0D2847')
    $blue = New-Object System.Drawing.SolidBrush (New-Color '#29B6F6')
    function T([float]$v, [float]$o) { $o + $v * $s }

    $g.FillRectangle($white, (T 52.5 $ox), (T 28 $oy), 3 * $s, 9 * $s)
    $g.FillEllipse($amber, (T 50 $ox), (T 22.5 $oy), 8 * $s, 8 * $s)
    $g.FillRectangle($grey, (T 25 $ox), (T 47 $oy), 5 * $s, 14 * $s)
    $g.FillRectangle($grey, (T 78 $ox), (T 47 $oy), 5 * $s, 14 * $s)

    $head = New-Object System.Drawing.Drawing2D.GraphicsPath
    Add-RoundRect $head (T 30 $ox) (T 36 $oy) (48 * $s) (38 * $s) (7 * $s)
    $g.FillPath($white, $head)

    foreach ($cx in 44, 64) {
        $g.FillEllipse($navy, (T ($cx - 7) $ox), (T 46 $oy), 14 * $s, 14 * $s)
        $g.FillEllipse($blue, (T ($cx - 3.5) $ox), (T 49.5 $oy), 7 * $s, 7 * $s)
    }
    $mouth = New-Object System.Drawing.Drawing2D.GraphicsPath
    Add-RoundRect $mouth (T 44 $ox) (T 65 $oy) (20 * $s) (4 * $s) (2 * $s)
    $g.FillPath($navy, $mouth)
}

function New-Canvas([int]$w, [int]$h) {
    $bmp = New-Object System.Drawing.Bitmap $w, $h, ([System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $g.TextRenderingHint = [System.Drawing.Text.TextRenderingHint]::AntiAliasGridFit
    return @($bmp, $g)
}

# Iconița Play: pătrat plin, Google rotunjește singur colțurile
$bmp, $g = New-Canvas 512 512
$g.Clear((New-Color '#1565C0'))
$s = 512 / 108
Draw-Robot $g $s 0 0
$bmp.Save((Join-Path $out 'icon-512.png'), [System.Drawing.Imaging.ImageFormat]::Png)
$g.Dispose(); $bmp.Dispose()

# Imaginea de prezentare
$bmp, $g = New-Canvas 1024 500
$rect = New-Object System.Drawing.Rectangle 0, 0, 1024, 500
$grad = New-Object System.Drawing.Drawing2D.LinearGradientBrush $rect, (New-Color '#0D47A1'), (New-Color '#1E88E5'), 20
$g.FillRectangle($grad, $rect)
Draw-Robot $g 5.2 -60 -30
$title = New-Object System.Drawing.Font 'Segoe UI', 86, ([System.Drawing.FontStyle]::Bold), ([System.Drawing.GraphicsUnit]::Pixel)
$sub = New-Object System.Drawing.Font 'Segoe UI', 34, ([System.Drawing.FontStyle]::Regular), ([System.Drawing.GraphicsUnit]::Pixel)
$white = New-Object System.Drawing.SolidBrush (New-Color '#FFFFFF')
$amber = New-Object System.Drawing.SolidBrush (New-Color '#FFC107')
$g.DrawString('BrickPilot', $title, $white, 470, 150)
$dot = [char]0x00B7
$g.DrawString("Autopilot  $dot  Line  $dot  Follow", $sub, $amber, 476, 270)
$bmp.Save((Join-Path $out 'feature-graphic-1024x500.png'), [System.Drawing.Imaging.ImageFormat]::Png)
$g.Dispose(); $bmp.Dispose()

Write-Output 'ok'
