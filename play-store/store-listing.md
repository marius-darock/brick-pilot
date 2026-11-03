# BrickPilot - fișa din Google Play

Fișiere în acest folder:
- `icon-512.png` - iconița aplicației (512x512)
- `feature-graphic-1024x500.png` - imaginea de prezentare
- `screenshots/` - capturi de ecran de pe telefon (2400x1200, raport 2:1)
- `privacy-policy.html` - politica de confidențialitate (trebuie urcată pe un site public, link-ul se pune în Play Console)
- AAB-ul semnat: `app/build/outputs/bundle/release/app-release.aab`

Date aplicație:
- Pachet: `ro.awebdesign.brickpilot` (nu se mai poate schimba după primul upload)
- Versiune: 1.5.0 (versionCode 6)
- Categorie: Instrumente (Tools)
- Etichete sugerate: Robotică, Educație, Hobby

---

## Română (ro-RO)

### Titlu (max 30)
BrickPilot: robot autonom

### Descriere scurtă (max 80)
Telefonul devine creierul robotului: ocolește obstacole, urmărește linii.

### Descriere completă (max 4000)
BrickPilot transformă telefonul tău în creierul unui robot construit din piese de construcție. Telefonul stă pe robot, privește prin camera din spate și conduce motoarele prin Bluetooth. Toată inteligența rulează pe telefon, fără internet și fără cont.

TREI MODURI DE MERS
• Ocolire obstacole: robotul merge prin cameră, vede pereții, ușile și mobila și le ocolește singur. Iese și din colțuri.
• Urmărire linie: lipește pe podea o bandă izolieră neagră, iar robotul o urmărește, inclusiv prin curbe.
• Urmărire obiect: robotul se întoarce spre cel mai apropiat obiect și îl urmează, păstrând distanța.

CE VEZI PE ECRAN
Peste imaginea camerei apare exact ce „vede” robotul: o grilă cu podeaua liberă în verde și obstacolele în roșu, sau punctele unde a găsit linia. Așa calibrezi robotul direct pe podeaua ta: covor, parchet sau gresie.

REGLAJE FĂRĂ PROGRAMARE
• Prag de detecție pentru podele diferite
• Viteză reglabilă
• Inversarea sensului fiecărui motor, pentru orice montaj
• Buton STOP mare; o atingere pe imagine oprește și ea robotul

SIGURANȚĂ
Motoarele frânează automat dacă aplicația nu mai trimite comenzi, dacă ieși din aplicație sau dacă apeși STOP.

DE CE AI NEVOIE
• Un hub Bluetooth compatibil cu protocolul LEGO® Wireless Protocol (testat cu LEGO® Technic Hub 88012)
• Două motoare cu encoder pe porturile A (stânga) și B (dreapta)
• Un suport care ține telefonul pe robot, cu camera din spate privind înainte și puțin în jos
• Android 8.0 sau mai nou, cu Bluetooth Low Energy

CONFIDENȚIALITATE
Aplicația nu are acces la internet. Imaginile camerei sunt analizate doar în memoria telefonului și nu sunt salvate sau trimise nicăieri.

LEGO® este marcă înregistrată a LEGO Group, care nu sponsorizează, nu autorizează și nu susține această aplicație. BrickPilot este o aplicație independentă.

---

## English (en-US)

### Title (max 30)
BrickPilot: Robot Autopilot

### Short description (max 80)
Your phone drives the robot car: avoids obstacles, follows lines and objects.

### Full description (max 4000)
BrickPilot turns your Android phone into the brain of a robot car built with LEGO® Technic and Powered Up parts. The phone rides on the car, looks ahead through its rear camera and drives the motors over Bluetooth. Everything runs on the phone: no internet, no account, no ads.

TWO KINDS OF CAR
Pick your chassis when the app starts; change it any time with the small TYPE button.
• Type 1, differential drive: two motors on the rear wheels, left on port A and right on port B. The car steers by driving the wheels at different speeds and can spin in place.
• Type 2, real steering: a steering motor at the front on port A and a drive motor on port B. On every connect the app calibrates the steering by itself: full left, full right, then it centres the wheels and uses that as straight ahead. It never pushes the steering against its end stops.

THREE DRIVING MODES
• Obstacle avoidance: drives around walls, doors and furniture and gets itself out of corners.
• Line following: stick black tape on the floor and the car follows it through curves. Stray marks and lines at the side are ignored, and if the car loses the line it reverses along its own path to find it again.
• Object following: the car locks onto an object by its colour (a ball, a box, a toy) and follows it, keeping its distance. Walls and furniture no longer steal the target.

SMART STEERING FOR TYPE 2 CARS
A steering car tends to over-correct at speed. BrickPilot predicts where the line will be when the wheels react, turns the wheels less at higher speed, slows down in sharp curves and learns from its own swings. What it learns is saved for the next run.

SEE WHAT THE ROBOT SEES
The camera view shows free floor in green and obstacles in red, the line it is following, or a box around the target it is chasing. Tune it right on your own floor: carpet, parquet or tiles.

TUNE IT WITHOUT CODING
• Detection threshold for different floors
• Speed and steering strength sliders
• Reverse any motor to match your build
• Landscape or portrait: the screen follows however the phone is mounted
• Big STOP button; tapping the camera view stops the car too

SAFETY
The motors brake if the app stops sending commands, when you leave the app or when you press STOP.

COMPATIBLE LEGO® HUBS
BrickPilot works with LEGO® hubs that use the LEGO® Wireless Protocol (Powered Up / Control+):
• LEGO® Technic Hub 88012 (Control+), tested on real cars of both types
• LEGO® Powered Up Hub 88009, same protocol and ports
• LEGO® BOOST Move Hub 88006, type 1 with its built-in motors
Motors: Powered Up / Control+ motors with a built-in rotation sensor, such as the Technic L and XL motors, on ports A and B.
LEGO® SPIKE™, MINDSTORMS® and WeDo 2.0 hubs use a different protocol and are not supported.

WHAT YOU NEED
• A compatible hub with two motors on ports A and B
• A mount that holds the phone on the car, rear camera facing forward and slightly down
• Android 8.0 or newer with Bluetooth Low Energy

PRIVACY
The app has no internet access. Camera frames are analysed only in the phone's memory and are never saved or sent anywhere.

LEGO®, Powered Up, BOOST, SPIKE™ and MINDSTORMS® are trademarks of the LEGO Group, which does not sponsor, authorize or endorse this app. BrickPilot is an independent app.

---

## Răspunsuri pentru formularele din Play Console

### Siguranța datelor (Data safety)
- Aplicația colectează sau partajează vreunul dintre tipurile de date cerute? **Nu.**
- Motiv: aplicația nu are permisiunea INTERNET, deci nu poate trimite date. Imaginile camerei sunt procesate doar pe dispozitiv și nu sunt stocate. Locația (doar Android 11 și mai vechi) e cerută de sistem pentru scanarea Bluetooth și nu e citită.
- Criptare în tranzit / ștergerea datelor: nu se aplică, nu există date colectate.

### Acces la aplicație (App access)
- Toate funcțiile sunt disponibile fără restricții (fără cont, fără login).
- Notă pentru recenzent: aplicația are nevoie de un hub Bluetooth compatibil LEGO® Wireless Protocol pentru a mișca motoarele; fără hub, camera și detecția funcționează și se văd pe ecran.

### Reclame
- Aplicația conține reclame? **Nu.**

### Clasificarea conținutului (questionnaire IARC)
- Categorie: Toate celelalte tipuri de aplicații (All Other App Types / Utility).
- Violență, sex, limbaj, droguri, jocuri de noroc, interacțiune între utilizatori, partajare locație, achiziții digitale: **Nu** la toate.
- Rezultat așteptat: PEGI 3 / Everyone.

### Public țintă
- Recomandare: 13+ (13-15, 16-17, 18+). Aplicația e o unealtă de robotică pentru hobby, nu e făcută special pentru copii.
- Dacă alegi și grupe sub 13 ani, aplicația intră în programul pentru familii, cu cerințe suplimentare.

### Declarații
- Aplicație de știri: Nu. Aplicație de sănătate: Nu. Aplicație guvernamentală: Nu. Servicii financiare: Nu.
- Permisiuni sensibile: CAMERA (motiv: robotul vede podeaua), BLUETOOTH_SCAN/CONNECT (motiv: comunicare cu hubul robotului). Locația doar pe Android 11 și mai vechi, pentru scanarea Bluetooth.
