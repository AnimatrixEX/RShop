# RShop — Android Game Store

## 1. Objectif du projet

Créer une application Android native capable de fonctionner à partir d'Android 13 et de servir de **Game Store** (store + téléchargement/installation, sans launcher), avec une interface inspirée des stores de consoles modernes.

L'application doit permettre à l'utilisateur de :

- parcourir un catalogue de jeux ;
- récupérer automatiquement les informations des jeux depuis un site web autorisé ;
- afficher jaquettes, captures, titre, description, taille et version ;
- télécharger les fichiers des jeux ;
- afficher la progression du téléchargement ;
- installer/extraire automatiquement les jeux dans un répertoire choisi par l'utilisateur ;
- gérer les jeux déjà installés ;
- supprimer les jeux ;
- vérifier les mises à jour ;
- rechercher et filtrer les jeux ;
- fonctionner correctement avec une manette.

Le projet doit être conçu en priorité pour les appareils Android portables de type Retroid, Ayn, Anbernic, etc., tout en restant compatible avec un smartphone/tablette Android classique.

---

# 2. Contraintes principales

## Android

Minimum :

- Android 13 / API 33
- architecture Android moderne
- Kotlin
- Jetpack Compose
- Material 3

Ne pas utiliser d'API obsolètes lorsque des alternatives modernes existent.

Le programme doit être capable de fonctionner sans Google Play Services lorsque cela est raisonnablement possible.

---

# 3. Interface utilisateur

L'interface doit ressembler davantage à un **store de console** qu'à une application Android classique.

Inspirations :

- PlayStation Store
- Xbox Store
- Nintendo eShop
- Steam Big Picture
- launchers de consoles portables

Design :

- grandes jaquettes ;
- cartes de jeux ;
- arrière-plans dynamiques ou images de couverture ;
- navigation horizontale ;
- catégories ;
- recherche ;
- bibliothèque ;
- favoris ;
- page détaillée du jeu ;
- boutons Installer / Mettre à jour ;
- animations fluides ;
- interface adaptée aux écrans 16:9 et 16:10.

Prévoir deux modes d'utilisation :

1. tactile ;
2. manette / contrôleur Bluetooth.

La navigation complète doit être possible sans écran tactile.

Le focus de navigation doit être clairement visible.

---

# 4. Écrans

Créer au minimum :

## Home

Contient :

- jeux récemment ajoutés ;
- jeux populaires ;
- jeux récemment mis à jour ;
- catégories ;
- recommandations.

## Store

Catalogue complet.

Fonctions :

- recherche ;
- tri ;
- filtres ;
- catégories ;
- pagination/lazy loading.

## Game Details

Afficher :

- couverture ;
- titre ;
- description ;
- captures d'écran ;
- taille ;
- version ;
- date de mise à jour ;
- plateforme ;
- genre ;
- bouton Installer ;
- bouton Mettre à jour.

## Downloads

Afficher :

- téléchargements actifs ;
- progression ;
- vitesse ;
- taille téléchargée ;
- taille totale ;
- temps restant ;
- pause ;
- reprise ;
- annulation.

## Library

Afficher uniquement les jeux installés.

Chaque jeu doit avoir :

- Mettre à jour ;
- Supprimer ;
- informations.

## Settings

Permettre notamment :

- choix du répertoire des jeux ;
- comportement des téléchargements ;
- Wi-Fi uniquement ;
- suppression des fichiers temporaires ;
- thème ;
- langue ;
- informations sur l'application.

---

# 5. Système de récupération du catalogue

L'application doit pouvoir récupérer les informations depuis un site web autorisé.

IMPORTANT :

Le système doit être conçu comme un module indépendant appelé `Scraper`.

Architecture :

```text
Scraper
    ↓
Website Adapter
    ↓
HTML
    ↓
Parser
    ↓
Game model
    ↓
Local database
    ↓
UI
```

Ne jamais mélanger le parsing HTML avec l'interface Android.

Créer une abstraction :

```kotlin
interface GameSource {
    suspend fun getGames(page: Int): List<Game>
    suspend fun getGameDetails(id: String): GameDetails
    suspend fun search(query: String): List<Game>
}
```

Créer ensuite des adaptateurs spécifiques :

```text
GameSource
   ├── WebsiteSource
   ├── JsonApiSource
   └── LocalSource
```

Le scraper doit pouvoir :

- télécharger une page HTML ;
- parser le HTML ;
- extraire les informations ;
- détecter les liens de téléchargement ;
- extraire les images ;
- extraire les métadonnées ;
- gérer la pagination ;
- gérer les erreurs réseau ;
- gérer les changements mineurs de structure du site.

Utiliser une bibliothèque adaptée au parsing HTML, par exemple Jsoup.

---

# 6. Respect du site source

Le scraper doit uniquement être utilisé sur des sites dont l'utilisation et l'accès automatisé sont autorisés.

Respecter :

- robots.txt lorsqu'il est applicable ;
- conditions d'utilisation ;
- rate limits ;
- droits d'auteur ;
- authentification lorsque nécessaire.

Ne jamais contourner :

- CAPTCHA ;
- protections anti-bot ;
- DRM ;
- restrictions d'accès ;
- paywalls ;
- authentification.

Ajouter un système de rate limiting afin de ne pas surcharger le serveur.

---

# 7. Modèle Game

Prévoir un modèle similaire :

```kotlin
data class Game(
    val id: String,
    val title: String,
    val description: String?,
    val coverUrl: String?,
    val screenshots: List<String>,
    val downloadUrl: String?,
    val version: String?,
    val sizeBytes: Long?,
    val platform: String?,
    val genre: String?,
    val sourceUrl: String?
)
```

Le modèle doit pouvoir évoluer.

Ne pas dépendre directement de l'HTML dans le reste de l'application.

---

# 8. Base de données locale

Utiliser Room.

Stocker localement :

- jeux ;
- jeux installés ;
- versions ;
- favoris ;
- historique ;
- métadonnées ;
- état des téléchargements si nécessaire.

L'application doit rester utilisable lorsque le catalogue n'est temporairement pas accessible.

---

# 9. Téléchargement

Créer un DownloadManager interne moderne.

Fonctionnalités :

- téléchargement en arrière-plan ;
- progression ;
- pause si possible ;
- reprise ;
- annulation ;
- gestion des erreurs ;
- retry ;
- téléchargement multipart uniquement si le serveur le permet ;
- vérification de l'intégrité.

Éviter de charger les fichiers entièrement en RAM.

Les gros fichiers doivent être téléchargés en streaming.

---

# 10. Vérification

Lorsque le serveur fournit un hash SHA-256 :

```text
Downloaded file
      ↓
SHA-256
      ↓
Expected hash
      ↓
MATCH → installation
ERROR → supprimer le fichier et recommencer
```

Ne jamais considérer un téléchargement comme valide uniquement parce que HTTP retourne 200.

---

# 11. Installation

L'application doit gérer plusieurs types de fichiers :

```text
.zip
.7z
.tar
.tar.gz
```

L'architecture doit permettre d'ajouter d'autres formats plus tard.

Exemple :

```text
Download
   ↓
Temporary directory
   ↓
Integrity check
   ↓
Extraction
   ↓
<GameDirectory>
   ↓
Installation completed
```

Utiliser des fichiers temporaires.

Si l'installation échoue, nettoyer correctement les fichiers temporaires.

Ne jamais extraire aveuglément des archives contenant des chemins comme :

```text
../../file
```

Prévenir les attaques de path traversal.

---

# 12. Gestion du stockage Android

Android 13 impose des contraintes de stockage.

Ne jamais supposer que :

```text
/storage/emulated/0/Games
```

est directement accessible en écriture.

Utiliser les mécanismes Android appropriés, notamment le Storage Access Framework lorsque nécessaire.

L'utilisateur doit pouvoir sélectionner le répertoire de destination.

Conserver correctement la permission persistante accordée par l'utilisateur.

---

# 13. Lancement des jeux — HORS PÉRIMÈTRE

Décision du projet : **RShop n'a pas de launcher.** L'application est uniquement un store + gestionnaire de téléchargements/installation de ROMs.

Le lancement reste le rôle du frontend ou des émulateurs déjà installés sur l'appareil.

Ne pas créer de `GameLauncher`, de bouton « Jouer », ni d'intents vers des émulateurs.

---

# 14. Architecture du projet

Utiliser une architecture propre :

```text
app/
├── data/
│   ├── database/
│   ├── network/
│   ├── scraper/
│   └── repository/
│
├── domain/
│   ├── model/
│   ├── repository/
│   └── usecase/
│
├── ui/
│   ├── home/
│   ├── store/
│   ├── details/
│   ├── downloads/
│   ├── library/
│   └── settings/
│
├── download/
├── installation/
└── navigation/
```

Utiliser :

- MVVM ;
- Repository pattern ;
- Use Cases lorsque pertinent ;
- Kotlin Coroutines ;
- Flow / StateFlow ;
- Hilt pour l'injection de dépendances.

Éviter la surarchitecture inutile.

---

# 15. Réseau

Utiliser :

- Retrofit si une API existe ;
- OkHttp ;
- Jsoup pour le scraping HTML ;
- Kotlin Coroutines.

Tous les appels réseau doivent être asynchrones.

Prévoir :

- timeout ;
- retry ;
- gestion offline ;
- erreurs HTTP ;
- erreurs DNS ;
- serveur indisponible.

---

# 16. Cache

Les images doivent être mises en cache.

Le catalogue doit également être mis en cache.

Objectif :

```text
Internet disponible
       ↓
Synchronisation
       ↓
Base locale
       ↓
Application utilisable offline
```

L'utilisateur doit pouvoir continuer à consulter sa bibliothèque sans connexion.

---

# 17. Sécurité

Ne jamais :

- exécuter directement du contenu provenant d'une page web ;
- faire confiance aveuglément aux URLs ;
- télécharger silencieusement des fichiers exécutables ;
- contourner les protections d'un site ;
- stocker des mots de passe en clair.

Valider :

- URL ;
- taille ;
- type de fichier ;
- hash ;
- chemins d'extraction.

---

# 18. Performance

L'application doit être optimisée pour les appareils Android portables.

Objectifs :

- démarrage rapide ;
- scrolling fluide ;
- images chargées progressivement ;
- aucune opération lourde sur le thread principal ;
- faible consommation mémoire ;
- faible consommation batterie.

Les listes doivent utiliser LazyColumn / LazyVerticalGrid.

Les images doivent être chargées avec Coil.

---

# 19. Configuration du site

Ne pas coder en dur la structure du site dans toute l'application.

Créer une configuration :

```kotlin
data class ScraperConfig(
    val baseUrl: String,
    val gameListSelector: String,
    val titleSelector: String,
    val coverSelector: String,
    val downloadSelector: String,
    val nextPageSelector: String?
)
```

À terme, permettre de changer de source sans réécrire l'application.

---

# 20. Synchronisation

Au démarrage :

```text
Launch
 ↓
Load local database
 ↓
Display UI immediately
 ↓
Check network
 ↓
Update catalogue
 ↓
Update UI
```

Ne jamais bloquer l'interface pendant la synchronisation.

---

# 21. Mises à jour

Comparer :

```text
Local version
        VS
Remote version
```

Si :

```text
remote > local
```

afficher :

```text
MISE À JOUR DISPONIBLE
```

Permettre à l'utilisateur de lancer la mise à jour.

---

# 22. Design

Le design doit être premium.

Éviter une simple interface Android composée de boutons standards.

Utiliser :

- grandes images ;
- cartes ;
- animations ;
- transitions ;
- effets de profondeur subtils ;
- focus animé ;
- typographie claire ;
- navigation par catégories.

Prévoir une palette sombre par défaut.

Le design doit être adapté à une utilisation depuis 1 à 3 mètres sur un écran de console portable ou TV.

---

# 23. Tests

Créer :

- tests unitaires du scraper ;
- tests des parsers ;
- tests du repository ;
- tests du téléchargement ;
- tests d'intégrité ;
- tests d'installation ;
- tests de navigation.

Le scraper doit avoir des fixtures HTML locales afin que les tests ne dépendent pas constamment du site réel.

---

# 24. Développement par phases

Ne pas essayer de développer tout le projet simultanément.

## Phase 1

Créer le projet Android.

Objectif :

```text
Android 13
↓
Compose
↓
Navigation
↓
Home / Store / Library / Settings
```

## Phase 2

Créer le modèle Game + Room.

## Phase 3

Créer le scraper.

Tester avec une vraie page du site autorisé.

## Phase 4

Afficher le catalogue réel.

## Phase 5

Créer le téléchargement.

## Phase 6

Créer l'installation/extraction.

## Phase 7

Créer la bibliothèque.

## Phase 8

Supprimée (pas de launcher).

## Phase 9

Ajouter les mises à jour.

## Phase 10

Polissage UI + manette + performances.

---

# 25. Règle importante pour Claude

Avant de coder une fonctionnalité importante :

1. analyser l'architecture ;
2. identifier les contraintes Android 13 ;
3. proposer la solution ;
4. vérifier les dépendances ;
5. implémenter ;
6. compiler ;
7. corriger les erreurs ;
8. tester.

Ne pas générer de faux code simplement pour avancer.

Lorsque quelque chose dépend du comportement réel d'Android, vérifier la documentation officielle ou tester plutôt que supposer.

Toujours privilégier une solution maintenable.

---

# 26. Objectif final

Le résultat final doit être une application ressemblant à :

```text
                GAME STORE
──────────────────────────────────────

HOME     STORE     LIBRARY     SEARCH

┌────────────────────────────────────┐
│                                    │
│          FEATURED GAME             │
│                                    │
│             [ COVER ]              │
│                                    │
│        TITLE OF THE GAME           │
│                                    │
│       [ INSTALL ]   [ + ]          │
│                                    │
└────────────────────────────────────┘

Recently Added

┌────────┐ ┌────────┐ ┌────────┐
│ COVER  │ │ COVER  │ │ COVER  │
│ GAME 1 │ │ GAME 2 │ │ GAME 3 │
└────────┘ └────────┘ └────────┘

Categories

ACTION   RACING   RPG   SIMULATION

──────────────────────────────────────
```

Le résultat doit donner l'impression d'un véritable magasin de jeux intégré à une console portable Android, et non d'un simple gestionnaire de téléchargements.