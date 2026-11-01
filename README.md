# BrickPilot

Aplicație Android care transformă un Samsung Galaxy S21 FE în creierul unui robot LEGO Technic.
Telefonul vede podeaua prin camera spate, detectează obstacolele și comandă prin Bluetooth LE
două motoare 88008 conectate la un Technic Hub 88012.

## Structura codului

| Fișier | Rol |
|---|---|
| `HubController.kt` | BLE: scanare, GATT, coadă de scrieri, watchdog, mesaje LWP |
| `FloorAnalyzer.kt` | Viziune: grila 9x7 în YUV, referința de podea, free[3] |
| `Avoider.kt` | Decizie: mers, încetinire, rotire pe loc, mixare pe motoare |
| `LineDetector.kt` | Viziune pentru modul linie: poziția liniei negre pe 5 benzi |
| `LineFollower.kt` | Decizie pentru modul linie: regulator PD, căutare când linia se pierde |
| `ObjectFollower.kt` | Decizie pentru modul urmărire: virează spre cel mai apropiat obiect, păstrează distanța |
| `GridOverlayView.kt` | Desenul grilei peste preview |
| `MainActivity.kt` | Legătura dintre toate, UI, permisiuni, cameră |

## Build

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n ro.awebdesign.brickpilot/.MainActivity
```

Necesită JDK 17 și Android SDK 36 (`local.properties` indică locația SDK-ului).

Teste: `./gradlew testDebugUnitTest`.

## Google Play

- Build pentru magazin: `./gradlew bundleRelease`, rezultă `app/build/outputs/bundle/release/app-release.aab`.
- Semnarea folosește `upload-keystore.jks` și `keystore.properties` din rădăcina proiectului. Ambele sunt
  excluse din git. **Fă-le copie de rezervă**: fără ele nu mai poți publica actualizări.
- La fiecare versiune nouă crește `versionCode` (și `versionName`) în `app/build.gradle.kts`.
- Texte, grafică și răspunsuri pentru formularele din Play Console: folderul `play-store/`.
- Numele din magazin nu conține „LEGO”: e marcă înregistrată. Marca apare doar în descriere, cu nota că
  aplicația nu e oficială.

## Pornirea robotului

1. Motoarele de tracțiune sunt în față: **A = motorul stâng**, **B = motorul drept**. Baterii noi în hub.
   Asocierea e în `Drivetrain.kt`.
2. Telefonul în suport, landscape, camera spate înainte, înclinată ~30 de grade în jos.
3. Pornește aplicația și acceptă permisiunile (cameră, Bluetooth).
4. Apasă **CONNECT**, apoi ține apăsat butonul verde de pe hub până clipește alb.
   La conectare butonul devine **DECONECTEAZĂ**, LED-ul hubului se face verde, iar sus apar porturile cu motor.
   Apăsat din nou, frânează motoarele și închide conexiunea.
5. Pune robotul pe podea liberă și verifică grila: în fața roților celulele trebuie să fie verzi,
   obstacolele roșii. Chenarul galben e podeaua de referință și trebuie să cadă pe podea curată.
6. Apasă **START** (LED albastru). **STOP** frânează imediat și iese din modul autonom.

## Modul linie (urmărește o linie neagră)

Butonul mov de sub CONNECT trece prin cele trei moduri: **MOD: OBSTACOLE**, **MOD: LINIE**,
**MOD: URMĂRIRE**. Modul se salvează.
Schimbarea modului în timpul mersului oprește robotul.

- Linia: bandă izolieră neagră sau bandă adezivă neagră, 1,5..3 cm lățime, pe podea deschisă la culoare.
  Contează contrastul: pe parchet închis la culoare linia neagră nu se vede bine.
- Pe ecran apar 5 benzi orizontale. Punctul galben de pe fiecare bandă e locul unde a găsit linia;
  liniile galbene le unesc. Un chenar roșu în jurul imaginii înseamnă că linia nu se vede.
- Linia e căutată de jos în sus: pe fiecare bandă se alege segmentul închis aflat lângă cel de pe banda
  de dedesubt. Un obiect închis la culoare din altă parte a imaginii e ignorat, la fel rosturile de
  parchet (prea înguste) și umbrele mari (prea late).
- Robotul virează spre linie proporțional cu cât e de departe de centru și încetinește în curbe.
- Dacă linia iese din imagine, se rotește spre partea unde a văzut-o ultima dată, cel mult 2 secunde,
  apoi se oprește pe loc. Dacă la START nu vede linia, stă pe loc.
- Pragul din slider nu contează în modul linie; sliderul de viteză da. Începe cu viteza la 60..80%.

Reglaje în `LineFollower.kt`:
- oscilează stânga-dreapta pe linie dreaptă: scade `KP`;
- iese din curbe: crește `KP`, scade viteza din slider sau crește `SLOWDOWN`;
- caută prea puțin sau prea mult după ce pierde linia: `SEARCH_MS`, `SEARCH_TURN`.

În `LineDetector.kt`: `MIN_CONTRAST` (cât de închisă trebuie să fie linia față de podea), `MIN_WIDTH`
și `MAX_WIDTH` (ce lățimi acceptă), `MAX_JUMP` (cât se poate muta linia între două benzi vecine).

## Oprire

- Butonul **STOP** ocupă toată coloana din dreapta.
- Cât robotul merge singur, o atingere oriunde pe imaginea camerei îl oprește la fel ca STOP.

## Siguranță

- Watchdog: fără comandă nouă 400 ms, ambele motoare frânează.
- La ieșirea din aplicație (onPause) robotul frânează.
- Coada BLE are maxim 8 mesaje; cele vechi sunt aruncate, iar comenzile noi pentru același port le înlocuiesc pe cele neexpediate.

## Dacă merge strâmb

**Robotul merge înapoi sau se rotește pe loc când ar trebui să meargă drept.**
Porturile sunt fixe în cod: **A = motorul stâng**, **B = motorul drept** (privind în direcția în care
se uită camera). Dacă ai cablurile invers, schimbă-le fizic între porturi.

Sus dreapta sunt două bife, salvate automat pe fiecare telefon:
- **Inv. A stânga** (implicit bifată) și **Inv. B dreapta** (implicit nebifată): sensul fiecărui motor.
  Motoarele sunt montate în oglindă, deci exact una trebuie bifată.
- se rotește pe loc la START pe drum liber: sunt bifate ambele sau niciuna; lasă una bifată;
- merge înapoi: mută bifa pe celălalt motor.

Bifele schimbă doar sensul de mers; virajul iese corect singur, din porturi. Sensurile sunt verificate
de testele automate din `app/src/test` (`./gradlew testDebugUnitTest`).

**Tracțiunea e în față.** Robotul virează prin diferența de viteză dintre roțile din față, iar roțile
din spate alunecă lateral. Dacă roțile din spate sunt fixe și au aderență mare, rotirea pe loc poate
fi greoaie. Atunci ajută anvelope mai netede în spate sau mărirea lui `SPIN_TURN` în `Avoider.kt`.

**Cum decide ce e podea.** Podeaua e urmărită pornind de la chenarul galben: rândul de jos din centru
spre margini, apoi fiecare coloană de jos în sus. O celulă e podea dacă seamănă cu podeaua de lângă ea,
deci reflexiile pe parchet lucios și umbrele largi (care se schimbă treptat) rămân podea, iar marginile
obiectelor (schimbare bruscă) devin obstacol. Numărul din celulă e diferența comparată cu pragul.

**Vede obstacole peste tot (prea mult roșu).** Crește pragul din slider (jos). Pe covoare cu model, 20..30.

**Liniile de parchet.** Fiecare celulă folosește mediana celor 25 de eșantioane, deci o linie subțire
dintre plăci nu contează. O celulă peste prag, dar fără nicio vecină peste prag, e ignorată (rămâne verde).
Numărul din celulă e distanța reală, deci poate depăși pragul chiar dacă celula e verde.
Dacă plăcile au nuanțe foarte diferite între ele, crește pragul.

**Intră în obstacole (prea mult verde).** Scade pragul. Pe parchet uniform merge și 8..12.
Obstacolele de aceeași culoare cu podeaua nu pot fi văzute de algoritm, oricât ai regla.

**Înclinarea camerei.** Primii 35% din cadru sunt ignorați. Rândul de jos al grilei trebuie
să fie podeaua imediat din fața roților.
- Dacă în grilă apar pereții îndepărtați sau mobila, înclină camera mai mult în jos.
- Dacă rândul de jos arată bara de protecție a robotului, înclină camera mai puțin sau mut-o mai în față.

**Viteza.** Sliderul „Viteză” (jos dreapta, 40..200%) scalează toate mișcările în toate modurile:
mers, viraje, mers înapoi și rotire. Se salvează automat.

**Ce face în fața unui obstacol (modul OBSTACOLE).**
1. Drum liber: merge înainte și virează ușor spre partea mai liberă.
2. Obstacol la distanță medie: încetinește și virează spre partea mai liberă.
3. Centrul blocat: se rotește pe loc 0,8 s spre partea mai liberă, fără întrerupere (`AUTO SPIN`).
4. **Colț:** dacă se blochează din nou în mai puțin de 2 s după o rotire, nu mai schimbă partea,
   se rotește tot în aceeași direcție, deci nu mai face stânga-dreapta. La al treilea blocaj la rând
   dă înapoi (`AUTO ESCAPE_BACK`) și face o rotire lungă, cam o jumătate de tură (`AUTO ESCAPE_SPIN`).

Duratele sunt pentru viteza 100%. La altă viteză se ajustează automat (la 50% durează dublu), ca
unghiul de rotire și distanța de mers înapoi să rămână aceleași.

**Reglaje ocolire**, în `Avoider.kt`:
- se întoarce prea mult sau prea puțin la un obstacol: `SPIN_MS`;
- nu iese din colțuri: crește `ESCAPE_SPIN_MS` sau `ESCAPE_BACK_MS`;
- intră prea repede în modul de colț: scade `CORNER_WINDOW_MS` sau crește `ESCAPE_AFTER`;
- virează prea brusc în mers: `CRUISE_GAIN`, `SLOW_TURN`.

## Modul urmărire (urmărește un obiect)

Folosește aceeași grilă ca modul obstacole. Ținta e cel mai apropiat obiect (celulele roșii cele mai
de jos). Robotul virează spre el și păstrează o distanță de ~2 rânduri libere din grilă: merge înainte
când obiectul se îndepărtează și stă pe loc când nu vede nimic.

Dă înapoi doar dacă obiectul din centrul imaginii vine spre el, cel mult 1,5 secunde la rând. Un lucru
apropiat din marginea imaginii (de exemplu o roată a robotului) nu îl mai face să dea înapoi, doar
să se întoarcă spre el.

- Merge bine cu un obiect de altă culoare decât podeaua (o minge, o cutie, picioarele cuiva).
- Nu lăsa obiectul să ajungă pe chenarul galben: acolo e podeaua de referință și detecția se strică.
- Pragul și viteza din slider se aplică și aici.
- Reglaje în `ObjectFollower.kt`: `TARGET_DISTANCE` (distanța păstrată), `KF` (cât de repede se apropie),
  `KT` (cât de tare virează spre țintă), `MAX_FORWARD`, `MAX_BACK`, `MAX_BACK_MS` (cât poate da înapoi
  la rând), `BACK_MAX_BEARING` (cât de centrată trebuie să fie ținta ca să dea înapoi).

**Hubul nu e găsit.** Hubul se vede doar cât timp clipește alb. Apasă CONNECT, apoi ține butonul verde.
Scanarea se oprește după 20 de secunde.
