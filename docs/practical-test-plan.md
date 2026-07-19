# Practical test plan

## Build artifacts

- Debug APK for device testing:
  - `app/build/outputs/apk/debug/app-debug.apk`
- Release APK generated locally:
  - `app/build/outputs/apk/release/app-release-unsigned.apk`
- Release Android App Bundle for Play Console upload flow:
  - `app/build/outputs/bundle/release/app-release.aab`

## Local install test

1. Enable developer options and USB debugging on the Android device.
2. Connect the device by USB.
3. Run:

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

4. Open `Cacheta & Buraco`.
5. Confirm:
   - Splash screen appears.
   - Login screen opens in landscape.
   - Nickname login enters the main menu.
   - 2-player and 4-player buttons select correctly.
   - Cacheta, Buraco, and Tranca room creation opens the lobby.
   - Back returns to the menu.

## Local network multiplayer smoke test

Use two Android devices on the same Wi-Fi network.

1. Device A: login, create a 2-player room.
2. Device B: login, tap `Entrar em Sala (Rede Local)`.
3. Confirm Device B discovers Device A's room.
4. Device B enters the room.
5. Device A starts the match.
6. Confirm both devices enter the match table.
7. Confirm each device received a different hand.
8. Test draw, select, meld, discard, and leave match.

## Local network multi-device seat test

Use three or four Android devices on the same Wi-Fi network.

1. Device A creates a 4-player Buraco or Tranca room.
2. Other devices join until the lobby allows the host to start.
3. Start the match.
4. Confirm every client receives a unique hand.
5. Confirm only the active seat can draw.
6. Confirm drawing from the deck serves the card only to the requesting device.
7. Confirm the top bar shows team scores as `Equipe A` versus `Equipe B` in 4-player rooms.
8. Confirm seats 0/2 score together and seats 1/3 score together.
9. Finish a round and confirm the host waits for reports from every seat before showing the summary.
10. Confirm a partner victory appears as `Sua equipe` and counts as a local victory.

## Rule smoke tests

### Cacheta

1. Create a Cacheta room.
2. Start the match and confirm each player receives 9 cards.
3. Confirm the table shows a separate `Vira` card.
4. Confirm the Cacheta wildcard is the next rank in the same suit as the vira.
5. With default room settings, confirm the discard pile starts empty.
6. Lower valid trincas/sequences and confirm an empty hand ends the round.

### Buraco

1. Create a Buraco room.
2. Confirm each player receives 11 cards and two mortos are created.
3. Confirm 2 is always treated as wildcard and makes a canastra dirty.
4. With `Canastra Limpa` enabled, confirm the player cannot finish with only dirty canastra.
5. Confirm charutos/trincas only work when enabled in the room.

### Tranca

1. Create a Tranca room.
2. Confirm each player receives 11 cards and two mortos are created.
3. Confirm 3 black locks the discard pile.
4. Confirm 3 red is lowered automatically when enabled.
5. Confirm 3 cannot be used in a normal meld.

## Play Store preparation

Use the files in `store-assets/` for the first listing draft:
  
- `icon-512.png`
- `feature-graphic-1024x500.png`
- `screenshots/01-login-1920x1080.png`
- `screenshots/02-menu-1920x1080.png`
- `screenshots/03-partida-1920x1080.png`

Before production publication, capture final screenshots from a real device or emulator and configure Play App Signing/upload key in Play Console.
