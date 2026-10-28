# FreeMusic v28.10.26.15

Explore page ka naya Tinder-style swipe deck, glass header, aur — sabse important — woh
purana crash jisse Explore aur Account pages app ko turant band kar dete the, ab fix
ho chuka hai. Is version mein deck ka ek hidden bug bhi fix hua hai jisse saare mood
cards collapse ho kar sirf **ek** card bachte the.

## What's new

- **Explore: Tinder-style swipe deck.** Ab Explore page par 2-column grid nahi hai.
  Upar ek single bada mood card (deck) hai, peeche do halke cards stack mein. Aap
  card par drag karke left ya right swipe kar sakte ho — right = **Open** (mood ka
  playlist page khulta hai), left = **Skip** (next mood). Card par hi OPEN/SKIP badge
  dikhta hai taaki direction ka andaza pehle se clear rahe. Ek smooth spring throw
  animation hai, aur peeche wali progress track batata hai ki kitne moods bache hain.
- **Explore header par artwork — bilkul Home page jaisa.** Jo mood card saamne hai,
  uska artwork Explore page ke top par full-width picture ki tarah lagta hai. Card
  change karte hi picture cross-fade hoti hai. Artwork ke palette se page ka colour
  bhi derive hota hai, to Explore page hamesha apne mood ke mood mein rehta hai.
- **Liquid glass buttons, deck ke andar.** Play / Shuffle / action buttons wahi
  liquid-glass treatment use karte hain jo baaki buttons mein hai — tint, directional
  highlight, hairline border. Koi backdrop capture nahi, isliye performance bhi sahi
  rehti hai.
- **Account & Integrations page redesign.** Naya Profile page — avatar, stats (hours
  listened, tracks played, playlists, sources), aur Feed / Challenge / Badge tabs.
  Settings mein Account & Integrations row ab seedha is page par le jaati hai.

## Deck ka hidden bug fix — ab "Card 1 of 49" dikhta hai

Pehle deck sirf **ek** card dikha raha tha (`Card 1 of 1`), jabki YouTube Music ke
`FEmusic_moods_and_genres` section mein 49 moods hote hain. Root cause: deck ke
duplicate-removal key mein sirf `browseId` use ho raha tha. Saare mood/genre buttons
ek hi browse-id family ke andar aate hain aur sirf `params` se alag hote hain — isliye
pehla mood har doosre mood ke against match ho jata tha aur poora deck collapse ho
kar ek card mein aa jata tha.

Ab key `browseId|params|title` par based hai, to har mood apni identity retain karta
hai. Deck ab sahi se 49 cards dikhata hai aur swipe karne par next mood par jaata hai.

## Crash fix — Explore aur Account pages app band kar rahe the

Aapne bataya ki Explore page tap karte hi app close ho jaata hai, aur Account page
mein bhi wahi problem hai. Root cause pata chala aur fix kiya gaya:

- App poore page ko ek `GraphicsLayer` mein record karta hai (taaki glass bars uska
  sample le sakein). Lekin agar usi recorded page ke *andar* koi glass surface apna
  backdrop sample kare, to render tree khud ko reference karne lagta hai — Android
  ka render node stack overflow karke **render thread me SIGSEGV** maar deta tha.
  Isi wajah se Explore card aur Account page dono mar rahe the.
- **Fix (2 level par):**
  1. Explore card ab lightweight glass use karta hai (tint + highlight + hairline) —
     koi backdrop capture nahi, to cycle banana hi nahi hai.
  2. Ek structural guard add kiya gaya: jab page record ho raha ho, andar ka koi bhi
     backdrop node sample lene ki koshish kare to use skip kar deta hai. Yeh poore
     crash-class ko band karta hai — sirf Explore nahi, har call site.
- Ab **Explore page, Account page, Profile page, Settings page** — sab bina crash ke
  khulte hain, glass ON ho ya OFF.

## Notes

- Verify kiya gaya dev aur prod release builds par, emulator me: Explore page khul
  raha hai, deck `Card 1 of 49` dikha raha hai, header artwork load ho rahi hai, left
  aur right dono swipe kaam kar rahe hain, Account & Integrations page khul raha hai —
  zero ANR, zero crash.
- Light aur dark dono themes me Explore render hua hai.
- Home page jaisa top-chrome treatment Explore par bhi lagaya gaya hai, taaki header
  picture par page ka colour na chadha aaye.

## Builds

- `app-prod-arm64-v8a-release.apk` — most modern phones
- `app-prod-armeabi-v7a-release.apk` — older 32-bit ARM
- `app-prod-x86_64-release.apk` — emulators / Chromebooks
- Universal APK is baar bhi drop hai: GitHub ke 100 MB push limit se upar jaata hai;
  har device in teen me se ek se covered hai.
