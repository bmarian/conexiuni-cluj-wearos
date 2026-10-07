# Google Play listing

Everything the Play Console asks for, ready to paste. Images are in this folder.

| Field | Value |
| --- | --- |
| Package | `online.bmarian.bus` |
| App name | Conexiuni Cluj |
| Category | Maps & Navigation |
| Free / paid | Free, no ads, no in-app purchases |
| Form factor | Wear OS only, works without a phone app |
| Privacy policy | https://github.com/bmarian/conexiuni-cluj-wearos/blob/main/PRIVACY.md |
| Website | https://bus.bmarian.online |
| App icon | `icon-512.png` |
| Feature graphic | `feature-graphic.png` |
| Wear OS screenshots | `screenshots/` (square, 454 px) |

## English (en-US)

**Short description** (80 characters max)

> Live bus and tram departures in Cluj-Napoca, right on your watch.

**Full description**

> Live CTP Cluj-Napoca buses and trams on your wrist.
>
> • Your favorite stops on the first screen, with their next departures
> • Departures for any stop, with a live countdown for tracked buses
> • Follow a bus: the countdown stays on your watch face, and the watch buzzes before it comes
> • Every line on the map, with the next arrival above each stop
> • Search stops and lines by voice or keyboard
> • Full timetables, from the current hour on
> • Stops near you, and where you are on the map
> • Turn the bezel to scroll and zoom
> • Copy your favorites from bus.bmarian.online by scanning a code
>
> Works on its own over Wi-Fi or LTE, no phone app needed.
>
> Live positions and GTFS data from Tranzy.ai, timetables from CTP Cluj-Napoca, map © OpenStreetMap contributors and CARTO. This app isn't affiliated with CTP Cluj-Napoca.

## Romanian (ro-RO)

**Short description**

> Plecările autobuzelor și tramvaielor din Cluj-Napoca, direct pe ceas.

**Full description**

> Autobuzele și tramvaiele CTP Cluj-Napoca, în timp real, pe încheietură.
>
> • Stațiile favorite pe primul ecran, cu următoarele plecări
> • Plecările din orice stație, cu numărătoare inversă pentru autobuzele urmărite în timp real
> • Urmărește un autobuz: numărătoarea rămâne pe cadran, iar ceasul vibrează înainte să vină
> • Fiecare linie pe hartă, cu următoarea sosire deasupra fiecărei stații
> • Caută stații și linii cu vocea sau tastatura
> • Orarul complet, de la ora curentă încolo
> • Stațiile din apropiere și locul tău pe hartă
> • Rotește rama ca să derulezi și să mărești harta
> • Copiază favoritele de pe bus.bmarian.online scanând un cod
>
> Merge singur, pe Wi-Fi sau LTE, fără aplicație pe telefon.
>
> Pozițiile în timp real și datele GTFS vin de la Tranzy.ai, orarele de la CTP Cluj-Napoca, harta © contribuitorii OpenStreetMap și CARTO. Aplicația nu este afiliată cu CTP Cluj-Napoca.

## Release notes for 1.0.0

> First release. / Prima versiune.

## Data safety

What the app sends off the watch, as Play defines it:

| Question | Answer |
| --- | --- |
| Does the app collect or share user data? | Yes, collects. Shares nothing. |
| Location | Not collected: it's used on the watch only. |
| App activity → App interactions | Collected: which stops and lines are opened, counted per hashed IP. Purpose: analytics. Not linked to an identity, required for the app to work. |
| Encrypted in transit | Yes, everything goes over HTTPS. |
| Can users ask for deletion? | There's no account and nothing tied to a person, so there's nothing to delete. |

Everything else (personal info, financial info, messages, photos, contacts, health, files, device IDs) is not collected.

## Other Play Console answers

- **Ads:** no.
- **Content rating:** a utility with no user-generated content, violence or purchases. It should come out as Everyone / PEGI 3.
- **Target audience:** 13 and older. It isn't made for children.
- **Government app:** no.
- **News app:** no.
- **Permissions:** location is used only in the foreground, for nearby stops and the map; there's no background location. Notifications are asked for when you first follow a bus.
- **Foreground service declaration:** type *Special use*. Paste this:
  > When the user taps Follow on a departure, the app keeps that bus's arrival countdown live as an ongoing activity on the watch face and vibrates shortly before the bus comes. It stops by itself once the bus has left the stop, after 90 minutes, or when the user taps Stop. It can't be started any other way.
  >
  > Video: tap a departure, tap Follow, lower the wrist, the countdown stays on the watch face.
