import java.io.FileNotFoundException;
import java.nio.file.*;
import java.util.*;

/**
 * Usage:
 *   java -Xmx2g DblpParsingDemo <dblp.xml|dblp.xml.gz> <dblp.dtd> [--limit=1000000]
 */
public class DblpParsingDemo {

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("""
                Usage:
                  java -Xmx2g DblpParsingDemo <dblp.xml|dblp.xml.gz> <dblp.dtd> [--limit=1000000]

                Exemple:
                  java -Xmx2g DblpParsingDemo dblp.xml.gz dblp.dtd --limit=500000
                """);
            System.exit(2);
        }

        Path xmlPath = Paths.get(args[0]);
        Path dtdPath = Paths.get(args[1]);

        long limit = Long.MAX_VALUE; // optionnel: s'arrêter après N publications
        for (int i = 2; i < args.length; i++) {
            String a = args[i];
            if (a.startsWith("--limit=")) limit = Long.parseLong(a.substring("--limit=".length()));
        }

        if (!Files.exists(xmlPath)) throw new FileNotFoundException("XML introuvable: " + xmlPath);
        if (!Files.exists(dtdPath)) throw new FileNotFoundException("DTD introuvable: " + dtdPath);

        // --------------------------------------------------------------------
        // IMPORTANT : limites d'expansion d'entités XML
        // --------------------------------------------------------------------
        // DBLP utilise un DTD qui définit beaucoup d'entités.
        // Le parseur XML de Java impose par défaut une limite sur le nombre
        // d'expansions d'entités pour se protéger d'attaques (type "Billion Laughs").
        //
        // Sur DBLP (fichier légitime), on dépasse souvent la limite par défaut (p.ex. 2500),
        // ce qui déclenche une erreur du type:
        //   JAXP00010001: The parser has encountered more than "2500" entity expansions...
        //
        // Ici, comme on parse un fichier connu + un DTD local (pas de réseau),
        // on désactive ces limites pour éviter l'erreur.
        //
        // À ne pas faire pour des XML non fiables.
        // --------------------------------------------------------------------
        System.setProperty("jdk.xml.entityExpansionLimit", "0");
        System.setProperty("jdk.xml.totalEntitySizeLimit", "0");
        System.setProperty("jdk.xml.maxGeneralEntitySizeLimit", "0");
        System.setProperty("jdk.xml.maxParameterEntitySizeLimit", "0");

        System.out.println("XML: " + xmlPath);
        System.out.println("DTD: " + dtdPath);
        if (limit != Long.MAX_VALUE) System.out.println("Limit: " + limit);

        long pubCount = 0;

        // --------------------------------------------------------------------
        // On crée le générateur DBLP dans un try-with-resources :
        //   - le constructeur démarre un thread de parsing en arrière-plan ;
        //   - les publications "parsé(e)s" sont déposées dans une file (queue) ;
        //   - gen.nextPublication() consomme cette file au fur et à mesure.
        //
        // Le try-with-resources garantit que gen.close() est appelé à la fin,
        // ce qui permet d'arrêter proprement le thread de parsing et de libérer
        // les ressources.
        // --------------------------------------------------------------------
        try (DblpPublicationGenerator gen = new DblpPublicationGenerator(xmlPath, dtdPath, 256)) {
            // Boucle de consommation : on traite les publications une par une,
            // jusqu'à atteindre la limite (si fournie) ou la fin du fichier.
            while (pubCount < limit) {
                
                // nextPublication() renvoie :
                //   - Optional.of(pub) si une publication est disponible ;
                //   - Optional.empty() si on a atteint la fin du flux (EOF).
                //
                // Cela évite d'utiliser null et oblige à gérer explicitement le cas EOF.
                Optional<DblpPublicationGenerator.Publication> opt = gen.nextPublication();
                if (opt.isEmpty()) break; // EOF

                pubCount++;
                DblpPublicationGenerator.Publication p = opt.get();

                List<String> authors = p.authors;
                if (authors == null || authors.isEmpty()) {
                    continue;
                }

                int k = authors.size();
                // 1er auteur
                String first = authors.get(0);

                // autres auteurs (peut être vide si k == 1)
                List<String> others = (k > 1) ? authors.subList(1, k) : List.of();
            }
        }
    }
}