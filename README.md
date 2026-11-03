# BrickPilot

An Android app that turns a phone into the brain of a LEGO Technic robot car.
The phone looks at the floor through its rear camera, finds obstacles or a line, and drives the
motors of a Technic Hub 88012 over Bluetooth LE.

Current version: **1.4.0** (versionCode 5).

Two kinds of car are supported:

- **Type 1, differential**: two motors on the rear wheels, steering by speed difference.
- **Type 2, steering**: a front steering motor (A) and a drive motor (B).

## Code layout

| File | Role |
|---|---|
| `HubController.kt` | BLE: scan, GATT, write queue, watchdog, LWP messages |
| `FloorAnalyzer.kt` | Vision: 9x7 grid in YUV, floor reference, free[3] |
| `Avoider.kt` | Decision: drive, slow down, spin in place, motor mixing |
| `LineDetector.kt` | Vision for line mode: black line on 5 bands, linked chains, ignores stray dots |
| `CommandHistory.kt` | Last driving commands, replayed backwards to return to the line |
| `ObjectTracker.kt` | Follow mode: recognises the target by its colour and tracks it |
| `LineFollower.kt` | Decision for line mode: PD controller, look-ahead at speed, search when the line is lost |
| `ObjectFollower.kt` | Decision for follow mode: turns towards the closest object, keeps the distance |
| `Drivetrain.kt` | Turns a drive command into port commands: differential (type 1) or steering + drive (type 2) |
| `LineSteerController.kt` | Type 2 line mode: delay-compensated steering, speed-scaled angle, auto slow-down, learning |
| `SteeringCalibrator.kt` | Type 2: finds both steering end stops and the straight-ahead center |
| `GridOverlayView.kt` | Grid drawn over the camera preview |
| `MainActivity.kt` | Glue: UI, permissions, camera, car type |

## Build

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n ro.awebdesign.brickpilot/.MainActivity
```

Needs JDK 17 and Android SDK 36 (`local.properties` points to the SDK).

Tests: `./gradlew testDebugUnitTest`.

## Google Play

- Store build: `./gradlew bundleRelease`, output in `app/build/outputs/bundle/release/app-release.aab`.
- Signing uses `upload-keystore.jks` and `keystore.properties` in the project root. Both are kept out
  of git. **Back them up**: without them you cannot publish updates.
- For every new release raise `versionCode` (and `versionName`) in `app/build.gradle.kts`.
- Store texts, graphics and Play Console form answers: the `play-store/` folder.
- The store name does not contain "LEGO", which is a registered trademark. The brand only appears in
  the description, together with a note that the app is not official.

## All texts are in English

Buttons, dialogs and status messages are in English (`app/src/main/res/values/strings.xml` and the
status lines in the Kotlin code). Code comments stay in Romanian.

## Starting the robot

1. Fresh batteries in the hub. Plug the motors in as described for your car type below.
2. Phone in its holder, landscape or portrait, rear camera facing forward, tilted about 30 degrees
   down. The screen follows the phone's position (see "Screen orientation" below).
3. Start the app and accept the permissions (camera, Bluetooth).
4. On the first start, pick the car type (see below).
5. Tap **CONNECT**, then hold the green button on the hub until it blinks white.
   Once connected the button turns into **DISCONNECT**, the hub LED goes green and the ports with a
   motor are shown at the top. Tapping it again brakes the motors and closes the connection.
6. Type 2 only: wait for the steering calibration to finish (`steering ±N°` in the status).
7. Put the robot on free floor and check the grid: cells in front of the wheels should be green,
   obstacles red. The yellow box is the floor reference and must sit on clean floor.
8. Tap **START** (blue LED). **STOP** brakes at once and leaves autonomous mode.

## Screen orientation

The app rotates with the phone, in all four directions, based on the phone's own sensor. It follows
the phone's position even when auto-rotate is turned off in the system settings, so the phone can be
mounted lying down (landscape) or standing up (portrait).

- Rotating does not restart the app: the hub connection, the steering calibration and autonomous
  driving all continue.
- The camera image is analysed upright in both orientations. In portrait the grid and the line bands
  cover a taller, narrower image, so the camera sees further ahead and less to the sides; check the
  tilt so the yellow floor box still sits right in front of the wheels.
- Landscape: buttons in the side columns, sliders in one row at the bottom.
- Portrait: CONNECT and MODE at the top left, the status under them, START and STOP side by side at
  the bottom, and one slider per row below them. The car type picker shows the two images one above
  the other.

## Car type

The app asks for the car type on the first start and remembers it. To change it later, tap the small
**TYPE** button in the bottom-left corner (only while disconnected). Each type keeps its own invert
settings.

### Type 1, differential

- **A = rear left motor**, **B = rear right motor**. The car turns by driving the wheels at different
  speeds and spins in place when needed.
- Checkboxes **Inv. A left** (checked by default) and **Inv. B right** (unchecked by default).

### Type 2, steering

- **A = front steering motor**, **B = drive motor**.
- **Steering calibration runs on every CONNECT**, for type 2 only:
  1. motor A turns the wheels all the way left until the end stop,
  2. then all the way right until the other end stop,
  3. the middle of the two ends is stored as the straight-ahead reference and the wheels go there.

  The ends are detected from the motor encoder. The app asks for the position on every step
  (every 50 ms) and keeps the furthest point reached, so a slow or stiff mechanism is followed all the
  way to the stop. An end counts as reached when the motor moves less than 6° in 400 ms. While
  driving, the wheels use 95% of the half travel and always stay at least 3° away from the stops, so
  the mechanism is never forced.
- **START** is enabled only after a successful calibration. If it fails, a dialog offers **Retry**.
  The steering needs physical left and right end stops; without them calibration gives up after
  8 seconds.
- **Steer** slider (bottom right, 100..400%, default 180%): how hard the wheels turn for the same
  command. A steering car turns wider than a differential one, so raise it if the car does not turn
  enough (220..250% works well for a stiff mechanism), lower it if it weaves on a straight line.
- The steering motor runs at full speed and power while driving, so it reaches the requested angle
  even with the wheels pressed on the floor.
- The car cannot spin in place, so the spins of the autonomous modes become reversing with the
  wheels turned the opposite way, like a three-point turn. The camera does not see behind the car
  during those manoeuvres.
- If the car turns the wrong way, check **Inv. A steer**; if it drives backwards, check
  **Inv. B drive**.

## Reaction time

The time from the camera seeing something to the motors reacting is kept short:

- The Bluetooth connection asks for the high priority interval (7.5..15 ms instead of 30..50 ms).
- Only commands that change are sent; the same speed is not resent on every frame.
- An unconfirmed write waits at most 25 ms before the next one goes out.
- The camera is locked at 30 frames per second, so it does not drop to 15 fps in dim rooms.
- In line mode the line position is only lightly smoothed, and above 80% speed the far bands count
  more, so the car starts a curve before it reaches it.

To measure it on a phone, read the log while driving:

```bash
adb logcat -s BrickPilot
```

It prints the camera frame rate, how old a frame is when it is analysed, the average Bluetooth write
time and the steering calibration results.

## Line mode (follows a black line)

The purple button under CONNECT cycles through the three modes: **MODE: OBSTACLES**, **MODE: LINE**,
**MODE: FOLLOW**. The mode is saved. Changing the mode while driving stops the robot.

- The line: black electrical tape or black adhesive tape, 1.5..3 cm wide, on a light floor.
  Contrast is what matters: on dark parquet a black line is hard to see.
- Five horizontal bands are shown on screen. The yellow dot on each band is where the line was found;
  yellow lines join them. A red frame around the image means the line is not visible.
- How the line is recognised (`LineDetector.kt`):
  - on each band every dark segment is a candidate, but only if it is clearly darker than the floor
    on **both** sides of it. Parquet joints (too narrow), large shadows (too wide) and the edge of a
    shadow coming into the picture (dark on one side only) are rejected;
  - the candidates are joined from the bottom band up into chains; a segment only joins one on the
    band below (or the one below that) that is close enough;
  - **the line must be seen on at least 2 linked bands.** A single dot on one band, a stain or the end
    of a line at the side of the track is ignored and no longer shown on screen;
  - among the chains, the longest one closest to where the line was in the previous frame wins, so a
    second line appearing at the side does not steal the robot from the line it follows.
- The robot turns towards the line in proportion to how far it is from the center and slows down in
  curves.
- **If the line leaves the image, the robot goes back the way it came**: it replays its last
  2.5 seconds of commands backwards (same arc, reversed), until the line is in view again. Only if it
  still does not see it does it turn towards the side where it saw the line last, for at most
  2 seconds, then it stops. If it does not see the line at START, it stays put.
- The threshold slider does not matter in line mode; the speed slider does. Start with 60..80% speed.

### Line mode on a type 2 car

A steering car follows the line with its own controller (`LineSteerController.kt`). A plain
proportional controller works at low speed but at high speed it over-corrects: the same wheel angle
turns the car faster the faster it goes, and the command reaches the wheels late (camera, Bluetooth,
steering motor, play in the mechanism). The car crosses the line, corrects too much the other way and
the swings grow until it loses the line. The new controller:

- **predicts where the line will be** when the command reaches the wheels (about 0.3 s ahead), so it
  straightens the wheels before crossing the line instead of after;
- **turns the wheels less at higher speed**, so the car turns at the same rate at any speed;
- **slows down** in curves and when the wheels are turned hard, to have time to correct;
- **learns**: if the car swings across the line twice within 2.5 s, it lowers its steering strength
  by 15% and its speed by 10%. After 3 s steady on the line it slowly returns towards the speed you
  set. If it stays on the same side of the line for a long time in a curve, it steers harder.
- **remembers** what it learned: the values are saved when you press STOP and loaded on the next
  start. The status line shows `learned steer N% speed N%`. **Long-press the "Steer" label** to reset
  them to 100%.
- when the line leaves the image, it reverses along the path it just drove, with the same wheel
  angles, until the line is back in view (at most 2.5 s). If that is not enough, it drives slowly
  forward with the wheels turned towards the side where it saw the line last, for at most 1.5 s,
  then stops. The status shows `AUTO RETRACE`, `AUTO SEARCH` or `AUTO LOST`.

The **Speed** and **Steer** sliders still apply. Start with Steer at 180% and let it learn; if it
still swings, lower Steer.

These behaviours are checked by a simulation in `app/src/test/.../LineSteerTest.kt`: a car model with
the real delay, a rate-limited steering motor and play in the mechanism. In that model the old
controller loses the line at high speed, as on the real car, and the new one stays on it at every
speed.

Tuning in `LineSteerController.kt`: `K_STEER` (how hard it steers), `LATENCY_S` (how far ahead it
predicts; raise it if it still overshoots), `SPEED_EXPONENT` (how much the angle drops with speed),
`SLOWDOWN`, `STEER_SLOWDOWN`, and the learning limits `MIN_LEARNED_GAIN`, `MIN_LEARNED_SPEED`.

Tuning in `LineFollower.kt` (type 1):
- weaves left and right on a straight line: lower `KP`;
- leaves curves: raise `KP`, lower the speed slider or raise `SLOWDOWN`;
- reacts too late at high speed: lower `LOOKAHEAD_FROM` so the look-ahead starts at a lower speed;
- searches too little or too long after losing the line: `SEARCH_MS`, `SEARCH_TURN`.

In `LineDetector.kt`: `MIN_CONTRAST` (how much darker than the floor the line must be), `MIN_WIDTH`
and `MAX_WIDTH` (accepted widths), `MAX_JUMP` (how far the line may move between two neighbouring
bands).

## Stopping

- The **STOP** button fills the whole right column.
- While the robot drives on its own, a touch anywhere on the camera image stops it just like STOP.
- STOP also stops a running steering calibration; the dialog then offers **Retry**.

## Safety

- Watchdog: with no new command for 400 ms, both motors brake.
- Leaving the app (onPause) brakes the robot.
- The BLE queue holds at most 8 messages; old ones are dropped, and a new command for a port replaces
  the one not yet sent.

## If it drives badly

**Type 1: the robot drives backwards or spins in place when it should go straight.**
The ports are fixed in code: **A = left motor**, **B = right motor** (looking in the direction the
camera faces). If your cables are swapped, swap them on the hub.

The two checkboxes at the top right are saved automatically on each phone:
- **Inv. A left** (checked by default) and **Inv. B right** (unchecked by default): the direction of
  each motor. The motors are mounted mirrored, so exactly one should be checked.
- spins in place at START on a free floor: both or neither are checked; leave one checked;
- drives backwards: move the check to the other motor.

The checkboxes only change the driving direction; steering follows from the ports. The directions are
covered by the unit tests in `app/src/test` (`./gradlew testDebugUnitTest`).

**Type 2: the car does not turn enough.** Check the `steering ±N°` value after calibration. If it is
small, the calibration did not reach the end stops: look at the "Steering end at" lines in the log.
If it is large and the car still turns too little, raise the **Steer** slider.

**How it decides what is floor.** The floor is traced starting from the yellow box: the bottom row
from the center outwards, then each column from the bottom up. A cell is floor if it looks like the
floor next to it, so reflections on glossy parquet and wide shadows (which change gradually) stay
floor, while object edges (sudden change) become obstacles. The number in a cell is the difference
compared with the threshold.

**Sees obstacles everywhere (too much red).** Raise the threshold slider (bottom). On patterned
carpets, 20..30.

**Parquet joints.** Each cell uses the median of 25 samples, so a thin line between boards does not
count. A cell over the threshold with no neighbour over the threshold is ignored (stays green).
The number in a cell is the real distance, so it can exceed the threshold even when the cell is green.
If the boards have very different shades, raise the threshold.

**Drives into obstacles (too much green).** Lower the threshold. On even parquet 8..12 works too.
Obstacles of the same colour as the floor cannot be seen by the algorithm, however you tune it.

**Camera tilt.** The top 35% of the frame is ignored. The bottom row of the grid should be the floor
right in front of the wheels.
- If distant walls or furniture show up in the grid, tilt the camera further down.
- If the bottom row shows the robot's bumper, tilt the camera less or move it further forward.

**Speed.** The **Speed** slider (bottom, 40..200%) scales every movement in every mode: driving,
turning, reversing and spinning. It is saved automatically.

**What it does in front of an obstacle (OBSTACLES mode).**
1. Free road: drives forward and steers gently towards the freer side.
2. Obstacle at medium distance: slows down and turns towards the freer side.
3. Center blocked: spins in place for 0.8 s towards the freer side, without interruption (`AUTO SPIN`).
4. **Corner:** if it gets blocked again less than 2 s after a spin, it keeps the same direction
   instead of going left-right. On the third block in a row it reverses (`AUTO ESCAPE_BACK`) and makes
   a long spin, about half a turn (`AUTO ESCAPE_SPIN`).

Durations are for 100% speed. At other speeds they adjust automatically (at 50% they last twice as
long), so the spin angle and reversing distance stay the same.

**Obstacle tuning**, in `Avoider.kt`:
- turns too much or too little at an obstacle: `SPIN_MS`;
- does not get out of corners: raise `ESCAPE_SPIN_MS` or `ESCAPE_BACK_MS`;
- enters corner mode too soon: lower `CORNER_WINDOW_MS` or raise `ESCAPE_AFTER`;
- turns too sharply while driving: `CRUISE_GAIN`, `SLOW_TURN`.

## Follow mode (follows an object)

**How the target is recognised** (`ObjectTracker.kt`). When there is no target yet, the closest
object in the obstacle grid becomes the target and the robot remembers **its colour**. From then on
it searches the whole picture for an area of that colour that does not look like the floor, and picks
the one closest to where the target was a moment ago. Walls, furniture, shadows and other obstacles
no longer steal the target, even if they are closer. The remembered colour follows slow changes of
light. A cyan box on screen shows the recognised target, and the status says `locked target`.

- The target is kept after STOP/START, so you can show the object to the robot first, check the cyan
  box, and then press START.
- If the target is not seen for about 20 frames (under a second), it is forgotten and the closest
  object becomes the new target.
- **Long-press the camera image** (while stopped, in FOLLOW mode) to forget the target and pick the
  closest object again.
- A target that differs from the floor only by brightness (for example grey on grey) cannot be
  locked; the robot then follows the closest object as before (`nearest object` in the status).

The robot turns towards the target and keeps a distance of about 2 free grid rows: it drives forward
when the object moves away and stays put when it sees nothing.

**On a type 2 car** the front wheels always point at the target. A steering car cannot turn in place,
so when the target is at the right distance but off to the side, it creeps forward slowly to turn
towards it. When it has to back off it reverses straight. The **Inv. A steer** checkbox must be set
the same way as in the other modes; following no longer needs it inverted.

It reverses only if the object in the middle of the image comes towards it, for at most 1.5 seconds
in a row. Something close at the edge of the image (for example one of the robot's wheels) no longer
makes it reverse, only turn towards it.

- Works well with an object of a different colour than the floor (a ball, a box, someone's feet).
- Do not let the object reach the yellow box: that is the floor reference and detection breaks.
- The threshold and speed sliders apply here too.
- Tuning in `ObjectFollower.kt`: `TARGET_DISTANCE` (distance kept), `KF` (how fast it closes in),
  `KT` (how hard it turns towards the target), `MAX_FORWARD`, `MAX_BACK`, `MAX_BACK_MS` (how long it
  may reverse in a row), `BACK_MAX_BEARING` (how centered the target must be for it to reverse).

**Hub not found.** The hub is only visible while it blinks white. Tap CONNECT, then hold the green
button. Scanning stops after 20 seconds.
