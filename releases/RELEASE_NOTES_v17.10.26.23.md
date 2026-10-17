## Profile, Edit Profile & Settings hub redesign (Brand Lime #BFFF00)

### Profile (My profile)
- New social-media layout replacing the Account & Integrations scrobbling screen
- Title bar: bell-with-5-dot badge + gear icon (opens Notifications / Settings)
- Hero: circular avatar with overlapping lime verified check, display name + handle, 4 stat tiles (hours / tracks / playlists / sources)
- 3-tab pill row (Feed selected/lime, Challenge, Badge)
- Sample post card with reactions, then empty-state cards for Challenge and Badge
- The top bar now reads 'My profile' instead of 'Account & Integrations'

### Edit Profile (4-field form)
- Full name, Phone number, Email, Username — in that order
- Username stored without leading at-sign (re-added on display)
- Lime 'Save Changes' button (full width, BrandLime fill, BrandLimeOn label)
- Dark 'Delete Account' button (full width, shows 'coming soon' toast for now)

### Settings hub
- New 8-row iOS-style 'Quick Settings' pinned at the top of the existing settings screen
- Account card (avatar + name + handle) tapping the Account scrobbling screen
- Pause notifications toggle (lime, persisted)
- Card: General settings / Dark mode (lime toggle) / Language (English) / My Contact
- Card: FAQ / Terms of service / User policy (opens configured URLs)
- Full-width Log Out button (red label, calls onSignOut / onSignIn)

### Brand layer
- New BrandLime = 0xFFBFFF00 (and BrandLimeSoft, BrandLimeOn) in Theme.kt
- Brand mark only — does NOT enter Material 3 colorScheme.primary, role colors stay neutral
- All new icons, toggles and the verified check badge use BrandLime

### AppSettings additions (8 new persisted flows)
- fullNameOverride, phoneOverride, emailOverride, usernameOverride
- notificationsPaused (Boolean)
- faqUrl, termsUrl, privacyUrl (String, placeholders set)

### Architecture
- New optional lambdas on AccountAndScrobblingScreen: onOpenNotifications, onOpenSettings (safe defaults)
- EditProfileDialog signature preserved — MainActivity call site unchanged
- SettingsScreen body extended (not rewritten); existing 12-section advanced groups remain below the new hub for power users

### Download
Three architecture-specific APKs (universal APK dropped to stay under GitHub 100MB limit):
- app-prod-arm64-v8a.apk — most modern devices
- app-prod-armeabi-v7a.apk — older 32-bit devices
- app-prod-x86_64.apk — emulators and Chromebooks
