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
> • Departures for any stop, with a live countdown for tracked buses
> • Every line on the map, with the next arrival above each stop
> • Full timetables, from the current hour on
> • Favorite stops and lines on the first screen
> • Stops near you
> • Turn the bezel to scroll and zoom
> • A tile with the next departures at your favorite stop
> • A complication that counts down to the next bus on your watch face
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
> • Plecările din orice stație, cu numărătoare inversă pentru autobuzele urmărite
> • Fiecare linie pe hartă, cu următoarea sosire deasupra fiecărei stații
> • Orarul complet, de la ora curentă încolo
> • Stațiile și liniile favorite pe primul ecran
> • Stațiile din apropiere
> • Rotește rama ca să derulezi și să mărești harta
> • Un tile cu următoarele plecări din stația favorită
> • O complicație care numără până la următorul autobuz, pe cadran
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
- **Permissions:** location is used only in the foreground, for nearby stops and the map. There's no background location.
