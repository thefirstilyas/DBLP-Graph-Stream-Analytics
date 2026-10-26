import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.IntConsumer;

/*
 * Projet Algorithmique 2 — Détection de communautés dans DBLP
 * INFO-F-203, ULB 2025/2026
 *
 * Tâche 1 : communautés de co-publication via Union-Find (graphe non orienté, online)
 * Tâche 2 : composantes fortement connexes via Kosaraju (graphe orienté filtré, seuil >= 6)
 *
 * Utilisation :
 *   java -Xmx6g DblpCommunityDetector <xml> <dtd> [--limit=N] [--interval=N] [--output=dir]
 */
public class DblpCommunityDetector {

    // intervalle d'affichage des états intermédiaires (tâche 1)
    private static final long DEFAULT_INTERVAL = 100_000L;

    // seuil minimum de co-publications pour garder une arête orientée (tâche 2)
    private static final int DIRECTED_THRESHOLD = 6;

    // nombre de top communautés à afficher/analyser
    private static final int TOP_K = 10;

    // mapping bidirectionnel nom <-> id entier (partagé entre les deux tâches)
    private final Map<String, Integer> authorToId = new HashMap<>();
    private final List<String> idToName = new ArrayList<>();

    // tâche 1 : Union-Find pour regrouper les co-auteurs en ligne
    private final UnionFind uf = new UnionFind(1 << 20);

    // tâche 2 : comptage online des paires orientées (premier auteur -> co-auteur)
    // [Partie réalisée avec l'aide de Claude (IA)]
    // on encode la paire (a, b) dans un seul long : ((long)a << 32) | b
    // cela évite de créer des objets wrapper à chaque publication et est bien plus rapide
    private final Map<Long, Integer> pairCounts = new HashMap<>();

    private long interval  = DEFAULT_INTERVAL;
    private Path outputDir = Paths.get("output");
    private long totalPublications = 0L;

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: java -Xmx6g DblpCommunityDetector <xml> <dtd> [options]");
            System.exit(2);
        }

        // désactiver les limites d'expansion d'entités XML (le fichier DBLP est local et fiable)
        System.setProperty("jdk.xml.entityExpansionLimit",        "0");
        System.setProperty("jdk.xml.totalEntitySizeLimit",        "0");
        System.setProperty("jdk.xml.maxGeneralEntitySizeLimit",   "0");
        System.setProperty("jdk.xml.maxParameterEntitySizeLimit", "0");

        Path xmlPath = Paths.get(args[0]);
        Path dtdPath = Paths.get(args[1]);
        long limit   = Long.MAX_VALUE;

        DblpCommunityDetector detector = new DblpCommunityDetector();

        for (int i = 2; i < args.length; i++) {
            String arg = args[i];
            if (arg.startsWith("--limit="))
                limit = Long.parseLong(arg.substring("--limit=".length()));
            else if (arg.startsWith("--interval="))
                detector.interval = Long.parseLong(arg.substring("--interval=".length()));
            else if (arg.startsWith("--output="))
                detector.outputDir = Paths.get(arg.substring("--output=".length()));
        }

        Files.createDirectories(detector.outputDir);

        System.out.println("=== Détecteur de communautés DBLP ===");
        System.out.println("XML    : " + xmlPath.toAbsolutePath());
        System.out.println("DTD    : " + dtdPath.toAbsolutePath());
        System.out.println("Sortie : " + detector.outputDir.toAbsolutePath());
        if (limit != Long.MAX_VALUE)
            System.out.println("Limite : " + limit + " publications");
        System.out.println();

        System.out.println(">>> Phase 1 : Lecture du flux DBLP...");
        long t0 = System.currentTimeMillis();
        detector.streamPublications(xmlPath, dtdPath, limit);
        System.out.printf("    Terminé : %d publications lues en %.1f s%n%n",
            detector.totalPublications, (System.currentTimeMillis() - t0) / 1000.0);

        System.out.println(">>> Tâche 1 : Écriture des résultats...");
        detector.writeTask1Results();
        System.out.println();

        System.out.println(">>> Tâche 2 : Analyse du graphe orienté filtré...");
        detector.runTask2();
        System.out.println();

        System.out.println("=== Programme terminé ===");
    }

    // --- Phase 1 : traitement en flux (online) ---

    // lit les publications une à une et met à jour les structures de données
    // affiche un état intermédiaire toutes les 'interval' publications (exigence online)
    private void streamPublications(Path xmlPath, Path dtdPath, long limit) throws Exception {
        System.out.printf("    Affichage intermédiaire toutes les %,d publications%n%n", interval);

        try (DblpPublicationGenerator gen = new DblpPublicationGenerator(xmlPath, dtdPath, 256)) {
            Optional<DblpPublicationGenerator.Publication> opt;
            while (totalPublications < limit) {
                opt = gen.nextPublication();
                if (opt.isEmpty()) break;

                processPublication(opt.get());
                totalPublications++;

                if (totalPublications % interval == 0)
                    printTask1IntermediateState();
            }
        }

        System.out.println("    État final :");
        printTask1IntermediateState();
        System.out.println();
    }

    // traite une publication : met à jour Union-Find (tâche 1) et les compteurs de paires (tâche 2)
    // les doublons d'auteurs au sein d'une même publication sont ignorés
    private void processPublication(DblpPublicationGenerator.Publication pub) {
        List<String> authors = pub.authors;
        if (authors == null || authors.isEmpty()) return;

        // conversion des noms en ids, avec déduplication intra-publication
        List<Integer> ids = new ArrayList<>(authors.size());
        Set<Integer> seen = new HashSet<>(authors.size() * 2);
        for (String name : authors) {
            int id = getOrCreateAuthorId(name);
            if (seen.add(id))
                ids.add(id);
        }

        if (ids.size() < 2) return;

        int first = ids.get(0);

        // tâche 1 : union de tous les co-auteurs (la transitivité de l'UF fait le reste)
        for (int i = 1; i < ids.size(); i++)
            uf.union(first, ids.get(i));

        // tâche 2 : on incrémente le compteur de la paire (premier auteur, co-auteur)
        for (int i = 1; i < ids.size(); i++) {
            int other = ids.get(i);
            // [Partie réalisée avec l'aide de Claude (IA)]
            // encoder la paire dans un long : 32 bits de poids fort pour 'first', 32 bits pour 'other'
            long key = ((long) first << 32) | (other & 0xFFFFFFFFL);
            pairCounts.merge(key, 1, Integer::sum);
        }
    }

    // retourne l'id d'un auteur, en le créant (avec son noeud UF) s'il n'existe pas encore
    private int getOrCreateAuthorId(String name) {
        return authorToId.computeIfAbsent(name, n -> {
            int id = idToName.size();
            idToName.add(n);
            uf.addNode();
            return id;
        });
    }

    // affiche l'état courant de la tâche 1 : nb de communautés + top-K tailles
    private void printTask1IntermediateState() {
        // on utilise un min-heap de taille TOP_K pour trouver les TOP_K plus grandes communautés en O(n)
        // (parcours unique de toutes les racines)
        PriorityQueue<Integer> minHeap = new PriorityQueue<>(TOP_K + 1);
        uf.forEachRootSize(sz -> {
            minHeap.offer(sz);
            if (minHeap.size() > TOP_K)
                minHeap.poll();
        });

        Integer[] top = minHeap.toArray(new Integer[0]);
        Arrays.sort(top, Collections.reverseOrder());

        System.out.printf("    Publications: %,10d | Communautés: %,10d | Top %d: %s%n",
            totalPublications, uf.getNumComponents(), TOP_K, Arrays.toString(top));
    }

    // --- Tâche 1 : écriture des résultats finaux ---

    private void writeTask1Results() throws IOException {
        // histogramme : taille -> nombre de communautés ayant cette taille
        Map<Integer, Integer> histogram = new TreeMap<>();
        uf.forEachRootSize(sz -> histogram.merge(sz, 1, Integer::sum));

        int total   = histogram.values().stream().mapToInt(Integer::intValue).sum();
        int maxSize = ((TreeMap<Integer, Integer>) histogram).lastKey();

        Path outFile = outputDir.resolve("task1_histogram.txt");
        try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(outFile))) {
            pw.println("# Tâche 1 : Histogramme des tailles des communautés (graphe non orienté)");
            pw.printf ("# Publications lues      : %,d%n", totalPublications);
            pw.printf ("# Auteurs totaux         : %,d%n", uf.nodeCount());
            pw.printf ("# Communautés totales    : %,d%n", total);
            pw.printf ("# Singletons             : %,d%n", histogram.getOrDefault(1, 0));
            pw.printf ("# Plus grande communauté : %,d membres%n", maxSize);
            pw.println();
            pw.printf("%-25s %s%n", "# Taille", "Nombre_de_communautés");
            for (Map.Entry<Integer, Integer> e : histogram.entrySet())
                pw.printf("%-25d %d%n", e.getKey(), e.getValue());
        }

        System.out.printf("    Auteurs           : %,d%n", uf.nodeCount());
        System.out.printf("    Communautés       : %,d%n", total);
        System.out.println("    Fichier écrit     : " + outFile);
    }

    // --- Tâche 2 : graphe orienté filtré + CFC + diamètre ---

    private void runTask2() throws IOException {
        int n = idToName.size();

        // construction des listes d'adjacence pour le graphe filtré et son transposé
        Map<Integer, List<Integer>> adj  = new HashMap<>();
        Map<Integer, List<Integer>> radj = new HashMap<>();
        Set<Integer> activeNodes = new HashSet<>();
        int edgeCount = 0;

        System.out.printf("    Paires (premier -> co-auteur) suivies : %,d%n", pairCounts.size());

        for (Map.Entry<Long, Integer> entry : pairCounts.entrySet()) {
            if (entry.getValue() >= DIRECTED_THRESHOLD) {
                // [Partie réalisée avec l'aide de Claude (IA)]
                // on récupère a et b depuis la clé long encodée dans processPublication()
                int a = (int) (entry.getKey() >>> 32);
                int b = (int) (entry.getKey() & 0xFFFFFFFFL);
                adj.computeIfAbsent(a,  k -> new ArrayList<>()).add(b);
                radj.computeIfAbsent(b, k -> new ArrayList<>()).add(a);
                activeNodes.add(a);
                activeNodes.add(b);
                edgeCount++;
            }
        }

        pairCounts.clear(); // libère la mémoire, plus besoin de pairCounts

        System.out.printf("    Arêtes avec compteur >= %d : %,d%n", DIRECTED_THRESHOLD, edgeCount);
        System.out.printf("    Auteurs dans le graphe filtré : %,d%n", activeNodes.size());

        // calcul des CFC par l'algorithme de Kosaraju
        System.out.println("    Calcul des CFC (algorithme de Kosaraju)...");
        long t0 = System.currentTimeMillis();
        List<List<Integer>> sccs = kosarajuSCC(n, activeNodes, adj, radj);
        System.out.printf("    %,d CFC trouvées en %.1f s%n",
            sccs.size(), (System.currentTimeMillis() - t0) / 1000.0);

        // histogramme des tailles
        Map<Integer, Integer> histogram = new TreeMap<>();
        for (List<Integer> scc : sccs)
            histogram.merge(scc.size(), 1, Integer::sum);

        int totalSCCs  = histogram.values().stream().mapToInt(Integer::intValue).sum();
        int singletons = histogram.getOrDefault(1, 0);

        Path histFile = outputDir.resolve("task2_histogram.txt");
        try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(histFile))) {
            pw.println("# Tâche 2 : Histogramme des tailles des CFC (graphe orienté, seuil >= " + DIRECTED_THRESHOLD + ")");
            pw.printf ("# Auteurs dans le graphe : %,d%n", activeNodes.size());
            pw.printf ("# Arêtes après filtrage  : %,d%n", edgeCount);
            pw.printf ("# CFC totales            : %,d%n", totalSCCs);
            pw.printf ("# Singletons             : %,d%n", singletons);
            pw.printf ("# CFC de taille >= 2     : %,d%n", totalSCCs - singletons);
            pw.println();
            pw.printf("%-25s %s%n", "# Taille", "Nombre_de_CFC");
            for (Map.Entry<Integer, Integer> e : histogram.entrySet())
                pw.printf("%-25d %d%n", e.getKey(), e.getValue());
        }
        System.out.println("    Fichier écrit : " + histFile);

        // top-K CFC : calcul du diamètre et écriture des membres
        sccs.sort((a, b) -> b.size() - a.size());
        int topK = Math.min(TOP_K, sccs.size());

        Path detailFile = outputDir.resolve("task2_top10.txt");
        try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(detailFile))) {
            pw.printf("# Tâche 2 : %d plus grandes CFC (graphe orienté filtré, seuil >= %d)%n%n",
                TOP_K, DIRECTED_THRESHOLD);

            for (int rank = 0; rank < topK; rank++) {
                List<Integer> scc = sccs.get(rank);
                System.out.printf("    CFC #%d (taille=%d) : calcul du diamètre...%n",
                    rank + 1, scc.size());

                long t1 = System.currentTimeMillis();
                int diameter = computeDiameter(scc, adj);
                System.out.printf("    -> Diamètre = %d (%.1f s)%n",
                    diameter, (System.currentTimeMillis() - t1) / 1000.0);

                pw.printf("=== CFC #%d ===%n", rank + 1);
                pw.printf("Taille   : %d%n", scc.size());
                pw.printf("Diamètre : %d%n", diameter);
                pw.println("Membres :");
                for (int id : scc)
                    pw.println("  " + idToName.get(id));
                pw.println();
            }
        }
        System.out.println("    Fichier écrit : " + detailFile);
    }

    // --- Algorithme de Kosaraju ---
    //
    // On ne peut pas utiliser le DFS récursif du cours ici : avec plusieurs millions de sommets,
    // Java lèverait un StackOverflowError. On implémente donc le DFS de façon itérative
    // avec une pile explicite (ArrayDeque). L'algorithme reste identique à celui du cours.
    // [Partie réalisée avec l'aide de Claude (IA) pour la version itérative]

    // retourne la liste des CFC du graphe orienté (chaque CFC = liste d'ids de sommets)
    private static List<List<Integer>> kosarajuSCC(
            int n,
            Set<Integer> activeNodes,
            Map<Integer, List<Integer>> adj,
            Map<Integer, List<Integer>> radj) {

        // étape 1 : DFS sur le graphe original → ordre de fin des sommets
        boolean[] visited = new boolean[n];
        Deque<Integer> finishOrder = new ArrayDeque<>();

        for (int v : activeNodes) {
            if (!visited[v])
                dfsFinish(v, adj, visited, finishOrder);
        }

        // étape 2 : DFS sur le graphe transposé dans l'ordre inverse de fin
        // chaque arbre DFS forme exactement une CFC
        boolean[] visited2 = new boolean[n];
        List<List<Integer>> sccs = new ArrayList<>();

        while (!finishOrder.isEmpty()) {
            int start = finishOrder.pollLast();
            if (!visited2[start]) {
                List<Integer> scc = new ArrayList<>();
                dfsCollect(start, radj, visited2, scc);
                sccs.add(scc);
            }
        }

        return sccs;
    }

    // DFS itératif (étape 1 de Kosaraju) : enregistre l'ordre de fin des sommets
    //
    // [Partie réalisée avec l'aide de Claude (IA)]
    // La difficulté : un DFS récursif se souvient automatiquement de là où il en est
    // dans la liste des voisins grâce à la pile d'appels. En itératif, on doit
    // simuler ça manuellement. On stocke dans la pile des paires {sommet, index_du_prochain_voisin}
    // pour savoir exactement quel voisin traiter à chaque reprise.
    private static void dfsFinish(
            int start,
            Map<Integer, List<Integer>> adj,
            boolean[] visited,
            Deque<Integer> finishOrder) {

        Deque<int[]> stack = new ArrayDeque<>();
        visited[start] = true;
        stack.push(new int[]{start, 0});

        while (!stack.isEmpty()) {
            int[] top = stack.peek();
            int node  = top[0];
            List<Integer> neighbors = adj.getOrDefault(node, Collections.emptyList());

            if (top[1] < neighbors.size()) {
                int next = neighbors.get(top[1]++);
                if (!visited[next]) {
                    visited[next] = true;
                    stack.push(new int[]{next, 0});
                }
            } else {
                // tous les voisins ont été explorés : ce sommet a fini
                stack.pop();
                finishOrder.addLast(node);
            }
        }
    }

    // DFS itératif simple (étape 2 de Kosaraju) : collecte tous les sommets atteignables
    // depuis `start` dans le graphe transposé → ils forment une CFC
    private static void dfsCollect(
            int start,
            Map<Integer, List<Integer>> radj,
            boolean[] visited,
            List<Integer> component) {

        Deque<Integer> stack = new ArrayDeque<>();
        stack.push(start);
        visited[start] = true;

        while (!stack.isEmpty()) {
            int node = stack.pop();
            component.add(node);
            for (int next : radj.getOrDefault(node, Collections.emptyList())) {
                if (!visited[next]) {
                    visited[next] = true;
                    stack.push(next);
                }
            }
        }
    }

    // --- Calcul du diamètre par BFS ---

    // calcule le diamètre d'une CFC dans son sous-graphe induit (BFS depuis chaque sommet)
    // on ne suit que les arêtes qui restent dans la CFC
    private int computeDiameter(List<Integer> scc, Map<Integer, List<Integer>> adj) {
        Set<Integer> sccSet = new HashSet<>(scc);
        int diameter = 0;

        // [Partie réalisée avec l'aide de Claude (IA)]
        // optimisation : on alloue le tableau dist une seule fois et on ne réinitialise
        // que les entrées qu'on a modifiées (au lieu de refaire Arrays.fill à chaque BFS)
        int[] dist = new int[idToName.size()];
        Arrays.fill(dist, -1);

        for (int source : scc) {
            List<Integer> touched = new ArrayList<>(scc.size());
            Deque<Integer> queue  = new ArrayDeque<>();

            dist[source] = 0;
            touched.add(source);
            queue.add(source);

            while (!queue.isEmpty()) {
                int curr = queue.poll();
                for (int next : adj.getOrDefault(curr, Collections.emptyList())) {
                    if (sccSet.contains(next) && dist[next] == -1) {
                        dist[next] = dist[curr] + 1;
                        if (dist[next] > diameter) diameter = dist[next];
                        touched.add(next);
                        queue.add(next);
                    }
                }
            }

            // réinitialisation sélective pour le prochain BFS
            for (int v : touched)
                dist[v] = -1;
        }

        return diameter;
    }

    // --- Structure Union-Find ---

    // - union pondérée par hauteur
    // - compression de chemin complète / two-pass
    //
    // On maintient un tableau sz[] séparé pour les tailles (nécessaire pour le top-K)
    // car l'union par hauteur ne met à jour que height[], pas la taille réelle.

    static final class UnionFind {

        private int[] parent;  // parent[i] = parent de i (parent[i] == i si racine)
        private int[] height;  // hauteur de l'arbre enraciné en i (valide si i est racine)
        private int[] sz;      // taille de la composante de i (valide si i est racine)

        private int count;         // nombre de noeuds créés
        private int numComponents; // nombre de composantes distinctes

        UnionFind(int initialCapacity) {
            parent = new int[initialCapacity];
            height = new int[initialCapacity];
            sz     = new int[initialCapacity];
        }

        // crée un nouveau noeud singleton et retourne son id
        // le doublement de capacité (pattern "dynamic array") est une technique classique
        // mais que nous n'avions pas encore utilisée en Java — l'IA nous a aidé ici
        int addNode() {
            int id = count++;
            if (id >= parent.length) {
                int newLen = parent.length * 2;
                parent = Arrays.copyOf(parent, newLen);
                height = Arrays.copyOf(height, newLen);
                sz     = Arrays.copyOf(sz,     newLen);
            }
            parent[id] = id;
            height[id] = 0;
            sz[id]     = 1;
            numComponents++;
            return id;
        }

        // trouve la racine de la composante de p
        // compression de chemin complète (two-pass, section 2.4 du cours)
        int find(int p) {
            int root = p;
            while (root != parent[root])
                root = parent[root];     // 1ère passe : trouver la racine
            while (p != root) {
                int next = parent[p];
                parent[p] = root;        // 2ème passe : rattacher tous les noeuds à la racine
                p = next;
            }
            return root;
        }

        // unit les composantes de p et q
        // union pondérée par hauteur
        boolean union(int p, int q) {
            int i = find(p);
            int j = find(q);
            if (i == j) return false;

            if      (height[i] < height[j]) { parent[i] = j; sz[j] += sz[i]; }
            else if (height[i] > height[j]) { parent[j] = i; sz[i] += sz[j]; }
            else                            { parent[j] = i; sz[i] += sz[j]; height[i]++; }

            numComponents--;
            return true;
        }

        int getSize(int x)       { return sz[find(x)]; }
        int getNumComponents()   { return numComponents; }
        int nodeCount()          { return count; }

        // appelle action avec la taille de chaque composante (une fois par composante)
        void forEachRootSize(IntConsumer action) {
            for (int i = 0; i < count; i++)
                if (parent[i] == i)
                    action.accept(sz[i]);
        }

        int[] getComponentSizes() {
            int[] sizes = new int[numComponents];
            int idx = 0;
            for (int i = 0; i < count; i++)
                if (parent[i] == i)
                    sizes[idx++] = sz[i];
            return sizes;
        }
    }
}
