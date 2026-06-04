import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.TurtleDocumentFormat;
import org.semanticweb.owlapi.model.*;
import org.semanticweb.owlapi.search.EntitySearcher;

import java.io.File;
import java.io.FileOutputStream;
import java.util.*;
import java.util.stream.Collectors;

public class SmallOntologyExtractor {

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.out.println("Usage: SmallOntologyExtractor <inputFile> <outputDir1hop> <outputDir2hop>");
            return;
        }

        String inputFile = args[0];
        String outputDir1hop = args[1];
        String outputDir2hop = args[2];

        // Create output directories
        new File(outputDir1hop).mkdirs();
        new File(outputDir2hop).mkdirs();

        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLDataFactory factory = manager.getOWLDataFactory();

        // Load ontology
        System.out.println("Loading ontology...");
        OWLOntology ontology = manager.loadOntologyFromOntologyDocument(new File(inputFile));
        System.out.println("Loaded " + ontology.getAxiomCount() + " axioms");

        // Get all individuals
        Set<OWLNamedIndividual> individuals = ontology.getIndividualsInSignature();
        System.out.println("Found " + individuals.size() + " individuals");

        // Extract TBox (schema axioms without individuals)
        Set<OWLAxiom> tboxAxioms = extractTBox(ontology);
        System.out.println("TBox contains " + tboxAxioms.size() + " axioms");

        // Process 1-hop extractions
        System.out.println("\nProcessing 1-hop extractions...");
        int[] results1hop = processExtractions(ontology, individuals, tboxAxioms, 1, outputDir1hop, manager, factory);

        // Process 2-hop extractions
        System.out.println("\nProcessing 2-hop extractions...");
        int[] results2hop = processExtractions(ontology, individuals, tboxAxioms, 2, outputDir2hop, manager, factory);

        // Final summary
        System.out.println("\n" + "=".repeat(50));
        System.out.println("EXTRACTION COMPLETE");
        System.out.println("=".repeat(50));
        System.out.println("1-hop: " + results1hop[0] + " successful, " + results1hop[1] + " failed");
        System.out.println("2-hop: " + results2hop[0] + " successful, " + results2hop[1] + " failed");
        System.out.println("Output directories:");
        System.out.println("  1-hop: " + outputDir1hop);
        System.out.println("  2-hop: " + outputDir2hop);
    }

    private static int[] processExtractions(OWLOntology ontology, Set<OWLNamedIndividual> individuals,
            Set<OWLAxiom> tboxAxioms, int hops, String outputDir,
            OWLOntologyManager manager, OWLDataFactory factory) {
        int processed = 0;
        int failed = 0;
        long startTime = System.currentTimeMillis();

        for (OWLNamedIndividual individual : individuals) {
            try {
                // Extract ABox for this individual (n-hop)
                Set<OWLAxiom> aboxAxioms = extractABoxForIndividual(ontology, individual, hops);

                if (aboxAxioms.isEmpty()) {
                    System.out.println("Warning: No ABox axioms found for individual: " + individual);
                }

                // Combine TBox and ABox
                Set<OWLAxiom> allAxioms = new HashSet<>(tboxAxioms);
                allAxioms.addAll(aboxAxioms);

                // Create new ontology
                IRI moduleIRI = IRI
                        .create("http://example.org/extracted/" + hops + "hop/" + getLocalName(individual.getIRI()));
                OWLOntology moduleOntology = manager.createOntology(allAxioms, moduleIRI);

                // Add ontology annotations
                addOntologyMetadata(manager, factory, moduleOntology, hops);

                // Generate filename
                String individualName = getLocalName(individual.getIRI());
                Set<String> types = getIndividualTypes(ontology, individual);
                String typePrefix = types.isEmpty() ? "Thing"
                        : types.stream().limit(2).collect(Collectors.joining("_"));

                String filename = sanitizeFilename(typePrefix + "_" + individualName) + ".ttl";
                File outputFile = new File(outputDir, filename);

                // Save as Turtle
                try (FileOutputStream fos = new FileOutputStream(outputFile)) {
                    manager.saveOntology(moduleOntology, new TurtleDocumentFormat(), fos);
                }

                // Cleanup
                manager.removeOntology(moduleOntology);

                processed++;
                if (processed % 100 == 0) {
                    long elapsed = System.currentTimeMillis() - startTime;
                    System.out.println(
                            "Processed " + processed + " individuals (" + hops + "-hop) in " + (elapsed / 1000) + "s");
                }

            } catch (Exception e) {
                failed++;
                System.err.println("Error processing " + individual + " (" + hops + "-hop): " + e.getMessage());
                if (failed <= 5) {
                    e.printStackTrace();
                }
            }
        }

        long totalTime = System.currentTimeMillis() - startTime;
        System.out.println("Completed " + hops + "-hop processing: " + processed + " successful, " + failed
                + " failed in " + (totalTime / 1000) + "s");

        return new int[] { processed, failed };
    }

    private static Set<OWLAxiom> extractABoxForIndividual(OWLOntology ontology,
            OWLNamedIndividual individual, int hops) {
        Set<OWLAxiom> aboxAxioms = new HashSet<>();

        if (hops == 1) {
            // For 1-hop: get axioms directly involving the target individual
            aboxAxioms = extract1HopABox(ontology, individual);

        } else if (hops == 2) {
            // For 2-hop: get 1-hop ABox + 1-hop ABox for each individual found in first
            // step

            // Step 1: Get the 1-hop ABox for the main individual
            Set<OWLAxiom> mainIndividual1Hop = extract1HopABox(ontology, individual);
            aboxAxioms.addAll(mainIndividual1Hop);

            // Step 2: Find all individuals mentioned in the 1-hop ABox
            Set<OWLNamedIndividual> individualsIn1Hop = new HashSet<>();
            for (OWLAxiom axiom : mainIndividual1Hop) {
                individualsIn1Hop.addAll(axiom.getIndividualsInSignature());
            }

            System.out.println("DEBUG: " + getLocalName(individual.getIRI()) + " 1-hop contains individuals: " +
                    individualsIn1Hop.stream().map(ind -> getLocalName(ind.getIRI())).collect(Collectors.toSet()));

            // Step 3: For each individual found in step 2, get their 1-hop ABox
            for (OWLNamedIndividual relatedIndividual : individualsIn1Hop) {
                if (!relatedIndividual.equals(individual)) { // Don't re-process the main individual
                    Set<OWLAxiom> related1Hop = extract1HopABox(ontology, relatedIndividual);
                    aboxAxioms.addAll(related1Hop);
                    System.out.println("DEBUG: Added " + related1Hop.size() + " axioms for " +
                            getLocalName(relatedIndividual.getIRI()));
                }
            }

        } else {
            // For 3+ hops, use the original BFS approach
            Set<OWLNamedIndividual> reachableIndividuals = new HashSet<>();
            findReachableIndividuals(ontology, individual, hops, reachableIndividuals);

            for (OWLAxiom axiom : ontology.getAxioms()) {
                Set<OWLNamedIndividual> axiomsIndividuals = axiom.getIndividualsInSignature();
                if (!axiomsIndividuals.isEmpty() &&
                        reachableIndividuals.containsAll(axiomsIndividuals)) {
                    aboxAxioms.add(axiom);
                }
            }
        }

        return aboxAxioms;
    }

    // Helper method to extract 1-hop ABox for a single individual
    private static Set<OWLAxiom> extract1HopABox(OWLOntology ontology, OWLNamedIndividual individual) {
        Set<OWLAxiom> aboxAxioms = new HashSet<>();

        for (OWLAxiom axiom : ontology.getAxioms()) {
            Set<OWLNamedIndividual> axiomsIndividuals = axiom.getIndividualsInSignature();

            // Include axioms that reference this individual
            if (axiomsIndividuals.contains(individual)) {
                aboxAxioms.add(axiom);
            }
        }

        return aboxAxioms;
    }

    private static Set<OWLAxiom> extractTBox(OWLOntology ontology) {
        Set<OWLAxiom> tboxAxioms = new HashSet<>();

        for (OWLAxiom axiom : ontology.getAxioms()) {
            // Include axioms that don't reference any individuals (pure schema axioms)
            if (axiom.getIndividualsInSignature().isEmpty()) {
                tboxAxioms.add(axiom);
            }
        }

        return tboxAxioms;
    }

    private static void findReachableIndividuals(OWLOntology ontology, OWLNamedIndividual startIndividual,
            int maxHops, Set<OWLNamedIndividual> reachableIndividuals) {
        Queue<OWLNamedIndividual> queue = new LinkedList<>();
        Map<OWLNamedIndividual, Integer> hopDistance = new HashMap<>();

        queue.offer(startIndividual);
        hopDistance.put(startIndividual, 0);
        reachableIndividuals.add(startIndividual);

        while (!queue.isEmpty()) {
            OWLNamedIndividual currentInd = queue.poll();
            int currentHop = hopDistance.get(currentInd);

            if (currentHop < maxHops) {
                // Find all individuals directly connected to current individual
                Set<OWLNamedIndividual> directlyConnected = findDirectlyConnectedIndividuals(ontology, currentInd);

                for (OWLNamedIndividual connectedInd : directlyConnected) {
                    if (!reachableIndividuals.contains(connectedInd)) {
                        reachableIndividuals.add(connectedInd);
                        hopDistance.put(connectedInd, currentHop + 1);
                        queue.offer(connectedInd);
                    }
                }
            }
        }
    }

    private static Set<OWLNamedIndividual> findDirectlyConnectedIndividuals(OWLOntology ontology,
            OWLNamedIndividual individual) {
        Set<OWLNamedIndividual> connected = new HashSet<>();

        // Check all object property assertions where this individual is subject or
        // object
        for (OWLObjectPropertyAssertionAxiom axiom : ontology.getAxioms(AxiomType.OBJECT_PROPERTY_ASSERTION)) {
            OWLIndividual subject = axiom.getSubject();
            OWLIndividual object = axiom.getObject();

            if (subject.equals(individual) && object instanceof OWLNamedIndividual) {
                connected.add((OWLNamedIndividual) object);
            } else if (object.equals(individual) && subject instanceof OWLNamedIndividual) {
                connected.add((OWLNamedIndividual) subject);
            }
        }

        // Check same individual axioms
        for (OWLSameIndividualAxiom axiom : ontology.getAxioms(AxiomType.SAME_INDIVIDUAL)) {
            Set<OWLIndividual> individuals = axiom.getIndividuals();
            if (individuals.contains(individual)) {
                for (OWLIndividual otherInd : individuals) {
                    if (!otherInd.equals(individual) && otherInd instanceof OWLNamedIndividual) {
                        connected.add((OWLNamedIndividual) otherInd);
                    }
                }
            }
        }

        // Check different individuals axioms
        for (OWLDifferentIndividualsAxiom axiom : ontology.getAxioms(AxiomType.DIFFERENT_INDIVIDUALS)) {
            Set<OWLIndividual> individuals = axiom.getIndividuals();
            if (individuals.contains(individual)) {
                for (OWLIndividual otherInd : individuals) {
                    if (!otherInd.equals(individual) && otherInd instanceof OWLNamedIndividual) {
                        connected.add((OWLNamedIndividual) otherInd);
                    }
                }
            }
        }

        return connected;
    }

    private static void addOntologyMetadata(OWLOntologyManager manager, OWLDataFactory factory,
            OWLOntology ontology, int hops) throws OWLOntologyChangeException {
        // Add label
        OWLAnnotation labelAnnotation = factory.getOWLAnnotation(
                factory.getRDFSLabel(),
                factory.getOWLLiteral("OWL2Bench Individual Subgraph (" + hops + "-hop)", "en"));
        manager.applyChange(new AddOntologyAnnotation(ontology, labelAnnotation));

        // Add comment
        OWLAnnotation commentAnnotation = factory.getOWLAnnotation(
                factory.getRDFSComment(),
                factory.getOWLLiteral(
                        "A Benchmark for OWL 2 Ontologies. Individual-centered " + hops + "-hop extraction."));
        manager.applyChange(new AddOntologyAnnotation(ontology, commentAnnotation));

        // Add version info
        OWLAnnotation versionAnnotation = factory.getOWLAnnotation(
                factory.getOWLVersionInfo(),
                factory.getOWLLiteral("OWL2Bench, ver 2020"));
        manager.applyChange(new AddOntologyAnnotation(ontology, versionAnnotation));
    }

    private static Set<String> getIndividualTypes(OWLOntology ontology, OWLNamedIndividual individual) {
        return EntitySearcher.getTypes(individual, ontology)
                .filter(cls -> cls instanceof OWLClass)
                .map(cls -> getLocalName(((OWLClass) cls).getIRI()))
                .filter(name -> !name.equals("NamedIndividual"))
                .collect(Collectors.toSet());
    }

    private static String getLocalName(IRI iri) {
        String iriString = iri.toString();
        if (iriString.contains("#")) {
            return iriString.substring(iriString.indexOf("#") + 1);
        } else if (iriString.contains("/")) {
            return iriString.substring(iriString.lastIndexOf("/") + 1);
        }
        return iriString;
    }

    private static String sanitizeFilename(String filename) {
        return filename.replaceAll("[^a-zA-Z0-9._-]", "_")
                .substring(0, Math.min(filename.length(), 100));
    }
}