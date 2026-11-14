# Cap Mystère

Application Android native (Kotlin + Jetpack Compose) pour les voyages-surprises. Un **hôte** prépare
un parcours ; un **voyageur** (le passager) le suit sans jamais connaître la destination : carte au
zoom verrouillé, aucune ville nommée, aucune distance totale — seulement « Dans 2,3 km, tournez à
droite ».

![Thème Pirates](docs/captures/planche-pirates-1.png)
![Thème Enquête noire](docs/captures/planche-noir-1.png)

D'autres planches : `docs/captures/`. Le plan d'architecture : [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md).

## État du projet

| Jalon | Contenu | État |
| --- | --- | --- |
| 1 | Squelette, thèmes pilotés par données, carte verrouillée | fait |
| 2 | Moteur de guidage, simulateur (itinéraire ou GPX), tests | fait, testé |
| 3 | Éditeur hôte, serveur Supabase, partage code / QR / lien | fait ; SQL testé sur Postgres local, **client Supabase non testé contre un vrai projet** |
| 4 | Défis (7 types), fragments d'indices, indices programmés | fait, testé |
| 5 | Hors ligne : tronçon en cache, tuiles du couloir, file d'attente | fait ; **téléchargement de tuiles non vérifié sur appareil** |
| 6 | Suivi en direct : position, réponses, photos, messages, passage d'étape | fait (testé sur le backend embarqué) |
| 7 | Sons, haptique, animations, 2 thèmes aboutis | fait |

**Ce qui a été vérifié sans appareil** : 40 tests unitaires du module `core`, un test de bout en bout
du voyage simulé (Robolectric), le schéma SQL sur un PostgreSQL 16 réel, le rendu de chaque écran
(Roborazzi), les builds debug et release signé.

**Ce qui n'a pas pu l'être** (l'environnement de développement n'a ni émulateur ni téléphone) :
l'exécution sur un appareil réel — vue MapLibre native, GPS, service de premier plan, caméra,
synthèse vocale, tuiles hors ligne — et le client Supabase face à un vrai projet. À tester en
priorité à la première installation ; voir « Premier essai » ci-dessous.

## Construire l'APK

Prérequis : JDK 17 ou plus récent, SDK Android (plateforme 36, build-tools 36). Le wrapper Gradle
télécharge le reste.

```bash
# SDK en ligne de commande, si Android Studio n'est pas installé
sdkmanager "platform-tools" "platforms;android-36" "build-tools;36.0.0"
echo "sdk.dir=/chemin/vers/android-sdk" > local.properties

./gradlew :app:assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### APK release signé localement

```bash
# 1. Une seule fois : créer votre clé (à conserver précieusement, hors du dépôt)
keytool -genkeypair -keystore capmystere.jks -alias capmystere \
        -keyalg RSA -keysize 2048 -validity 10000

# 2. Renseigner local.properties (non versionné) …
RELEASE_KEYSTORE=capmystere.jks
RELEASE_KEYSTORE_PASSWORD=…
RELEASE_KEY_ALIAS=capmystere
RELEASE_KEY_PASSWORD=…

# 3. … puis construire
./gradlew :app:assembleRelease        # app/build/outputs/apk/release/app-release.apk
```

Les quatre valeurs peuvent aussi être passées en variables d'environnement. Sans keystore, l'APK
release est produit non signé.

## Configuration

Tout se règle dans `local.properties` (ou en variables d'environnement). Rien n'est obligatoire.

| Clé | Défaut | Rôle |
| --- | --- | --- |
| `SUPABASE_URL` | vide | URL du projet Supabase. Vide = **mode local**. |
| `SUPABASE_ANON_KEY` | vide | Clé publique « anon » du projet. |
| `VALHALLA_URL` | `https://valhalla1.openstreetmap.de` | Serveur de calcul d'itinéraire. |
| `TILES_URL` | `https://tiles.openfreemap.org` | Tuiles vectorielles (schéma OpenMapTiles). |
| `SHARE_BASE_URL` | `capmystere://join/` | Préfixe du lien de partage. |
| `RELEASE_*` | vide | Signature (voir ci-dessus). |

### Mode local (sans serveur)

Sans Supabase, l'application fonctionne entièrement sur un seul téléphone : édition, aperçu
« comme le voyageur », simulation, et même un vrai voyage (l'hôte publie, puis saisit le code dans
« Je pars à l'aventure » sur le même appareil avant de le confier au passager). Le suivi en direct
depuis un autre téléphone, lui, exige le serveur.

### Brancher Supabase

1. Créer un projet sur [supabase.com](https://supabase.com).
2. **Authentication → Sign In / Providers** : activer **Anonymous sign-ins**.
3. **SQL Editor** : exécuter `supabase/schema.sql`, puis `supabase/storage.sql` (les deux sont rejouables).
4. **Project Settings → API** : copier l'URL et la clé `anon` dans `local.properties`.
5. Reconstruire l'APK. L'accueil affiche « Connecté » au lieu de « Mode local ».

Tester le schéma sans compte Supabase (PostgreSQL local requis) :

```bash
./gradlew :core:test && bash supabase/tests/run.sh    # → TOUS LES TESTS SQL PASSENT
```

Aucune Edge Function n'est nécessaire : toute la logique serveur tient dans des fonctions SQL
`SECURITY DEFINER` (voir ARCHITECTURE). Le backend est derrière l'interface `TripBackend` ; pour
quitter Supabase, il suffit d'en écrire une autre implémentation.

## Premier essai

1. **Je prépare un voyage** → *Nouveau voyage*. Touchez la carte : départ, puis étapes.
2. Icône réglages : titre, thème, public, mot mystère.
3. Sélectionnez une étape → *Message et défis*.
4. **Aperçu voyageur** : la simulation rejoue le parcours (vitesses ×1, ×4, ×16). *Rejouer un GPX*
   fait de même avec une trace enregistrée, ce qui permet d'éprouver les sorties de route.
5. Partage → *Publier* → code, QR et lien.

## Tests

```bash
./gradlew :core:test                               # moteur de guidage, GPX, règles serveur, anti-fuite
./gradlew :app:testDebugUnitTest                   # voyage simulé de bout en bout
./gradlew :app:testDebugUnitTest -Pscreenshots     # + planches dans app/build/screenshots/
```

## Ajouter un thème

Déposer un dossier `app/src/main/assets/themes/<id>/` contenant `theme.json` (palette jour/nuit,
polices, forme des cartes, décor, palette de carte MapLibre, illustrations en chemins SVG, sons,
voix, ton des textes par public, défis suggérés) et ses sons. Aucun code à écrire : le thème
apparaît dans le sélecteur. Modèles : `themes/pirates` et `themes/noir`. Le format est décrit par
`ThemeSpec.kt`.

Thèmes prévus, non livrés : Espace / SF, Fantasy, Road trip rétro, Romantique, Conte pour enfants.

## Services tiers et conditions d'usage

Vérifiées en novembre 2026 ; à revérifier avant toute diffusion large.

| Service | Usage | Conditions |
| --- | --- | --- |
| [OpenFreeMap](https://openfreemap.org) | Tuiles de carte | Gratuit, sans clé, sans limite de requêtes annoncée ; attribution affichée sur la carte. Financé par des dons. |
| Valhalla, instance FOSSGIS | Itinéraires | Gratuit, sans clé, « usage raisonnable », sans garantie de service. Pour un usage soutenu : auto-héberger Valhalla et régler `VALHALLA_URL`. |
| [Photon](https://photon.komoot.io) | Recherche de lieux (hôte) | Gratuit, sans clé, usage raisonnable. |
| Supabase | Serveur (facultatif) | Offre gratuite suffisante pour un usage privé. |

OpenRouteService et GraphHopper ont été écartés par défaut car ils exigent une clé ; l'interface
`RoutingProvider` permet de les ajouter.

## Choix ouverts, à trancher

- **Nom.** « Cap Mystère » est conservé. Autres pistes : *Azimut*, *Cap Inconnu*, *Sillage*.
- **Lien de partage.** `capmystere://join/CODE` fonctionne sans site web mais n'est cliquable que si
  l'application est installée. Un lien `https://` (App Links) demande un nom de domaine.
- **Noms de rues** sur la carte du voyageur : masqués par défaut, réglable par voyage.
- **Messages hors ligne.** Une étape validée sans réseau est enregistrée et synchronisée plus tard,
  mais son message reste sur le serveur jusque-là (sinon il faudrait le livrer d'avance, donc le
  rendre lisible avant l'arrivée).
- **Validation « automatique » des photos** : toute photo envoyée est acceptée. Pas d'analyse d'image.
- **Position en arrière-plan** : demandée en option ; le service de premier plan suffit dans la
  plupart des cas.
- **Taille de l'APK** (~70 Mo en debug) : MapLibre embarque ses bibliothèques natives pour quatre
  architectures. Un découpage par ABI la diviserait par trois.

## Divers

Le fichier `index` à la racine est le prototype web d'origine (Mapbox) ; il n'est pas utilisé par
l'application. Polices sous licence libre : voir `app/src/main/assets/fonts/LICENSES.md`.
