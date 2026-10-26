# Détection de communautés dans DBLP — INFO-F-203 Algorithmique 2

Projet 2025/2026 — Chaabane Youssef (000574459) & El Haichami Ilyas (000532152)

## Structure du projet

```
dblp-community/
├── src/
│   ├── DblpPublicationGenerator.java   # Parseur XML fourni par le cours (ne pas modifier)
│   ├── DblpParsingDemo.java            # Démo fournie par le cours (ne pas modifier)
│   ├── DblpCommunityDetector.java      # Programme principal — Tâches 1 et 2
│   └── dblp.dtd                        # DTD officielle DBLP
├── data/
│   └── dblp-2026-01-01.xml.gz          # Snapshot DBLP du 1er janvier 2026 (non inclus dans l'archive, à télécharger)
├── output/                             # Fichiers de sortie (créés automatiquement)
│   ├── task1_histogram.txt             # Histogramme des tailles de communautés (Tâche 1)
│   ├── task2_histogram.txt             # Histogramme des tailles de CFC (Tâche 2)
│   └── task2_top10.txt                 # Top 10 CFC avec taille et diamètre (Tâche 2)
├── rapport.pdf                         # Rapport final du projet
└── README.md                           # Documentation du projet
```


## Compilation

Depuis la **racine du projet** :


```bash
javac src/DblpPublicationGenerator.java src/DblpCommunityDetector.java
```

Les fichiers `.class` sont générés dans `src/`.

## Exécution

Depuis la **racine du projet** :

```bash
java -Xmx6g -cp src DblpCommunityDetector <xml> <dtd> [options]
```

### Arguments

| Argument       | Description                                                    | Requis |
|----------------|----------------------------------------------------------------|--------|
| `<xml>`        | Chemin vers le fichier DBLP (`.xml` ou `.xml.gz`)              | Oui    |
| `<dtd>`        | Chemin vers `dblp.dtd`                                         | Oui    |
| `--limit=N`    | S'arrêter après N publications (utile pour les tests)          | Non    |
| `--interval=N` | Fréquence d'affichage de l'état intermédiaire (défaut : 100000)| Non    |
| `--output=dir` | Répertoire de sortie (défaut : `output/`)                      | Non    |

### Exemple — Fichier complet

```bash
java -Xmx6g -cp src DblpCommunityDetector data/dblp-2026-01-01.xml.gz src/dblp.dtd --output=output
```

### Exemple — Test rapide (500 000 publications)

```bash
java -Xmx4g -cp src DblpCommunityDetector data/dblp-2026-01-01.xml.gz src/dblp.dtd --limit=500000 --interval=100000 --output=output
    
```

## Fichiers de sortie

| Fichier                      | Contenu                                                                    |
|------------------------------|----------------------------------------------------------------------------|
| `output/task1_histogram.txt` | Histogramme : taille → nombre de communautés (Tâche 1, graphe non orienté)|
| `output/task2_histogram.txt` | Histogramme : taille → nombre de CFC (Tâche 2, graphe orienté filtré)     |
| `output/task2_top10.txt`     | Top 10 CFC : taille, diamètre et liste des membres                         |

Les fichiers sont au format texte tabulé. Les lignes commençant par `#` sont des commentaires.

## Note sur la mémoire

L'exécution complète sur le snapshot DBLP requiert environ **6 Go de RAM** et environ **70 secondes**.
L'option `-Xmx6g` est donc recommandée. Si votre machine dispose de moins de mémoire,
utilisez `--limit=N` pour tester sur un sous-ensemble des publications.
