# Architecture

## Vue d'ensemble

```
:core  (Kotlin pur, testable sur JVM)          :app  (Android)
├─ model      Trip, Stage, Challenge,          ├─ theme     ThemeSpec (JSON) → CapTheme (Compose)
│             TravelerSnapshot…                ├─ map       style MapLibre généré, carte verrouillée,
├─ geo        distances, projection,           │            tuiles hors ligne
│             couloir, soleil                  ├─ data      Room, DataStore, TripBackend (local /
├─ guidance   Route, GuidanceEngine            │            Supabase), RoutingProvider (Valhalla)
├─ gpx        lecture GPX, simulateur          ├─ travel    TravelController, SyncEngine,
├─ challenge  normalisation, empreintes        │            TravelService (premier plan)
├─ routing    requête / réponse Valhalla       ├─ location  FusedLocationProvider
└─ server     TripServer (règles du jeu),      ├─ audio     voix, sons, haptique
              ServerDoc (document serveur)     └─ ui        accueil, voyageur, hôte
```

Pile : Compose + Material 3 (très personnalisé), Hilt, Room, WorkManager, coroutines/Flow, MapLibre
Native, CameraX + ZXing, OkHttp + kotlinx.serialization. Min SDK 26.

## Le secret de la destination

C'est la contrainte qui structure tout.

1. **Le voyageur ne reçoit qu'un `TravelerSnapshot`** : étapes déjà débloquées, cible du tronçon
   courant (position + rayon), défis accessibles, indices gagnés. Ni le nombre d'étapes, ni la
   suite, ni la destination.
2. **Cette projection est calculée côté serveur.** `TripServer.snapshot()` en est la référence,
   testée (un test sérialise l'instantané et vérifie qu'aucun secret n'y figure). Les fonctions SQL
   de `supabase/schema.sql` en sont la transcription, testée de la même façon sur un vrai Postgres.
3. **Sur Supabase, la table `trips` est illisible pour le voyageur** (RLS). Il ne passe que par des
   fonctions `SECURITY DEFINER` : `join_trip`, `traveler_snapshot`, `validate_stage`,
   `submit_answer`, `push_positions`.
4. **L'itinéraire est calculé par le client**, de sa position vers la cible courante — la seule
   qu'il connaisse. Le serveur ne livre donc jamais de géométrie au-delà du tronçon en cours.
5. **La carte ne reçoit qu'une fenêtre de l'itinéraire** (60 m derrière, 700 m devant), zoom figé,
   gestes désactivés, style sans toponymie ni point d'intérêt.
6. **Les instructions sont reconstruites localement** à partir du type de manœuvre. Les textes de
   Valhalla, qui citent villes et panneaux, ne sont pas utilisés.
7. **Les réponses aux défis ne quittent pas l'hôte en clair.** Le voyageur reçoit des empreintes
   SHA-256 salées : vérification possible hors ligne, sans rien révéler. Les récompenses restent
   sur le serveur jusqu'au verdict.
8. **La validation d'étape est contrôlée par le serveur** : distance à l'étape ≤ rayon + tolérance.

Limite assumée : la cible du tronçon courant est connue de l'appareil. Un voyageur qui fouillerait
la mémoire de l'application verrait la prochaine étape — jamais les suivantes.

## Guidage

`GuidanceEngine` est une fonction d'état sans dépendance : position → recalage sur la polyligne
(fenêtre glissante, pour ne pas sauter sur un tronçon parallèle ou un aller-retour) → prochaine
manœuvre et distance → annonces (lointaine, proche, immédiate, une fois chacune) → sortie de route
(après plusieurs positions éloignées, tolérance élargie si le GPS est imprécis) → arrivée dans le
rayon. Seuils par profil : voiture, vélo, marche.

`TravelController` l'alimente (GPS ou simulation), déclenche voix, sons et vibrations, demande un
recalcul en cas de sortie de route (ou affiche « Faites demi-tour dès que possible » hors ligne),
valide l'étape et envoie les positions.

## Hors ligne

- Dès qu'un tronçon est calculé, il est mis en cache (Room) et les tuiles d'un couloir de 4 km de
  large sont pré-téléchargées (`OfflineTiles`, zooms 11 à 14).
- Le guidage est entièrement local.
- Validations d'étape, réponses et positions passent par une file (`outbox`) rejouée dans l'ordre
  au retour du réseau, par l'application ou par WorkManager si elle a été fermée. Les opérations
  serveur sont idempotentes.

## Backends

`TripBackend` isole le serveur. `LocalBackend` exécute `TripServer` sur l'appareil (aperçu,
simulation, usage sans serveur) ; `SupabaseBackend` appelle les fonctions SQL par HTTP, sans SDK.
`BackendRouter` aiguille : les sessions locales restent locales même quand Supabase est configuré.

Le temps réel (websocket Phoenix) ne fait que signaler « quelque chose a changé » ; l'application
recharge alors l'état. Un rafraîchissement périodique sert de filet.

## Thèmes

Un thème est un fichier `theme.json`. Il pilote : couleurs jour et nuit, trois polices (titrage,
instruction, texte), forme des cartes, décor de fond, **style de carte MapLibre** (généré par
`MapStyleFactory` à partir d'une palette), illustrations vectorielles, sons, voix, vocabulaire
(« Escale », « Scène »…), répliques d'ambiance par manœuvre, narration, variantes par public et
défis suggérés. Le public ajuste en plus la taille des textes, le tutoiement, la voix par défaut
et le nombre d'essais.

L'instruction affichée reste toujours claire (« Tourne à droite ») ; le ton du thème vit dans la
réplique d'ambiance en dessous (« Barre à tribord ! »).
