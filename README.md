# Zap TV pour Android TV

Votre app IPTV dans le style de Netflix, en application Android TV : direct, films, séries, Ma liste et reprise de lecture. Elle se pilote entièrement à la télécommande.

- **Interface** : la page `app/src/main/assets/index.html`, affichée en plein écran.
- **Lecture** : un vrai lecteur Android (ExoPlayer). Il lit les formats courants en IPTV (MKV, TS, HLS, son AC3), contrairement à un navigateur.
- **Pas de blocage CORS** : les playlists et l'API Xtream se chargent depuis n'importe quel serveur, y compris en http.

## 1. Obtenir le fichier APK

### Option A : sans rien installer, avec GitHub (le plus simple)

1. Créez un compte gratuit sur github.com, puis un **nouveau dépôt** (« New repository »). Choisissez **Public**, ce qui permettra à la TV de télécharger l'APK sans se connecter.
2. Sur la page du dépôt, cliquez sur « uploading an existing file ». Glissez-y **tout le contenu** de ce dossier, y compris les dossiers `.github` et `gradle`, puis validez (« Commit changes »).
3. Ouvrez l'onglet **Actions**. La compilation démarre toute seule et prend environ 5 minutes.
4. Une fois terminée, l'APK est disponible dans **Releases**, à une adresse qui ne change pas :
   `https://github.com/VOTRE-NOM/VOTRE-DEPOT/releases/latest/download/zap-tv.apk`

Pour simplifier l'adresse à taper sur la TV, raccourcissez-la avec un service comme is.gd.

> Sur macOS, les fichiers qui commencent par un point (`.github`) sont masqués. Faites Cmd + Maj + . dans le Finder pour les voir.

### Option B : avec Android Studio

1. Installez Android Studio (developer.android.com/studio).
2. Faites **File › Open**, puis choisissez ce dossier. Attendez la fin de la synchronisation Gradle.
3. Faites **Build › Build App Bundle(s) / APK(s) › Build APK(s)**.
4. L'APK se trouve dans `app/build/outputs/apk/debug/app-debug.apk`. Il est signé avec la même clé que la version GitHub : les mises à jour s'installent par-dessus sans rien perdre.

## 2. Installer sur la TV

### Avec l'app Downloader (recommandé)

1. Sur la TV, installez **Downloader** (de AFTVnews) depuis le Play Store.
2. Autorisez-la à installer des applications : **Paramètres › Applications › Sécurité et restrictions › Sources inconnues › Downloader**. Selon la TV, cela peut aussi se trouver sous **Paramètres › Confidentialité › Sécurité**.
3. Ouvrez Downloader, tapez l'adresse de l'APK (étape 1), puis choisissez **Installer**.

### Autres moyens

- **Clé USB** : copiez l'APK sur la clé, puis ouvrez-le avec un gestionnaire de fichiers sur la TV (par exemple « File Commander » ou « X-plore »).
- **Depuis le téléphone** : installez l'app « Send files to TV » sur le téléphone et sur la TV, puis envoyez l'APK.
- **ADB**, pour les habitués : `adb connect IP-DE-LA-TV` puis `adb install zap-tv.apk`.

Zap TV apparaît ensuite dans la rangée d'applications de l'accueil, avec sa bannière.

## 3. À la télécommande

| Où | Touche | Effet |
|---|---|---|
| Partout | ← → ↑ ↓ | Se déplacer entre les affiches, les boutons et les rangées |
| Partout | OK | Ouvrir une fiche ou lancer la lecture |
| Partout | Retour | Fermer la fiche, revenir à l'accueil, puis quitter |
| Direct | ↑ ↓ ou CH+ CH- | Chaîne précédente ou suivante (dans la même catégorie) |
| Direct | Chiffres | Aller directement à une chaîne par son numéro |
| Direct | OK | Pause ou reprise |
| Films et séries | ← → | Ouvrir les commandes, puis avancer ou reculer |
| Films et séries | Retour | Revenir à la fiche (la position est gardée pour « Reprendre ») |

L'épisode suivant se lance tout seul à la fin d'un épisode.

## 4. Ajouter votre abonnement

Appuyez sur le bouton **Playlists** en haut à droite, puis :

- **Xtream Codes** (recommandé) : serveur, identifiant et mot de passe. Vous obtenez les affiches, résumés, saisons et épisodes.
- **Lien M3U** : l'adresse de la playlist.

Les identifiants restent sur la TV.

## 5. Réglages, VPN et coups de cœur (version 1.2)

Le bouton **Réglages**, en bas du menu de gauche, regroupe :

- **VPN** : « Activer le VPN gratuit » crée tout seul un accès Cloudflare WARP (gratuit, sans limite de données) et ne fait passer que Zap TV dans le tunnel. La première fois, Android demande d'autoriser la connexion VPN : choisissez **OK**. Vous pouvez aussi utiliser votre propre fichier WireGuard (par exemple Proton VPN Free). Une fois activé, le VPN se rallume à chaque ouverture de l'app.
- **Format des chaînes** : TS (recommandé) ou HLS. Si un format échoue, l'autre est essayé automatiquement.
- **Mémoire tampon** : Démarrage rapide, Équilibré ou Anti-coupure.
- **DNS sécurisé** : contourne les serveurs DNS du fournisseur d'accès.
- **Animations** : complètes ou allégées (plus fluide sur les TV peu puissantes).
- **Sources** et **Entretien** (actualiser le catalogue, vider le cache des images).

Dans la fiche d'un film ou d'une série, le **cœur** l'ajoute à vos coups de cœur (rubrique **J'aime**). L'accueil propose ensuite « Recommandé pour vous » et « Parce que vous avez aimé… ».

## Personnaliser

- **Nom de l'app** : `app/src/main/res/values/strings.xml`.
- **Bannière de l'accueil TV** : `app/src/main/res/drawable-xhdpi/banner.png` (320 × 180 px).
- **Icône** : `app/src/main/res/mipmap-*/ic_launcher.png`.
- **Interface** : `app/src/main/assets/index.html` (la même page fonctionne aussi dans un navigateur d'ordinateur).

N'utilisez que des sources dont vous avez les droits.
