import os
import re
import json
from pathlib import Path
from typing import Dict, Set, List, Tuple, Any, Optional
import argparse
from collections import defaultdict
import concurrent.futures
import multiprocessing


class OntologyAbstractor:
    """Ontology abstraction tool that handles both ns1: prefixed and full URI ontologies"""

    def __init__(self):
        self.global_mappings = {
            "classes": {},
            "object_properties": {},
            "data_properties": {},
            "individuals": {},
        }
        self.target_namespace = None
        self.namespace_mode = None  # 'prefix' or 'uri'
        self._compile_patterns()

    def _compile_patterns(self):
        """Pre-compile all regex patterns for better performance"""
        # Universal URI pattern
        self.uri_pattern = re.compile(r"<([^>]+)#([^>]+)>")
        self.namespace_pattern = re.compile(r"@prefix\s+(\w+):\s+<([^>]+)>")

        # Flexible prefix pattern (any prefix)
        self.prefixed_term_pattern = re.compile(r"\b(\w+):([A-Za-z][A-Za-z0-9_]*)\b")

        # Specific ns1 pattern (for backward compatibility)
        self.ns1_pattern = re.compile(r"\bns1:([A-Za-z][A-Za-z0-9_]*)\b")

        # Type declaration patterns
        self.class_type_pattern = re.compile(
            r"<([^>]+)#([^>]+)>\s+(?:rdf:type|a)\s+owl:Class"
        )
        self.obj_prop_type_pattern = re.compile(
            r"<([^>]+)#([^>]+)>\s+(?:rdf:type|a)\s+owl:ObjectProperty"
        )
        self.data_prop_type_pattern = re.compile(
            r"<([^>]+)#([^>]+)>\s+(?:rdf:type|a)\s+owl:(?:DatatypeProperty|AnnotationProperty)"
        )
        self.individual_type_pattern = re.compile(
            r"<([^>]+)#([^>]+)>\s+(?:rdf:type|a)\s+owl:NamedIndividual"
        )

        # Prefixed type declarations (flexible prefix)
        self.prefix_class_pattern = re.compile(
            r"(\w+):([A-Za-z][A-Za-z0-9_]*)\s+(?:rdf:type|a)\s+owl:Class"
        )
        self.prefix_obj_prop_pattern = re.compile(
            r"(\w+):([A-Za-z][A-Za-z0-9_]*)\s+(?:rdf:type|a)\s+owl:ObjectProperty"
        )
        self.prefix_data_prop_pattern = re.compile(
            r"(\w+):([A-Za-z][A-Za-z0-9_]*)\s+(?:rdf:type|a)\s+owl:(?:DatatypeProperty|AnnotationProperty)"
        )
        self.prefix_individual_pattern = re.compile(
            r"(\w+):([A-Za-z][A-Za-z0-9_]*)\s+(?:rdf:type|a)\s+owl:NamedIndividual"
        )

        # Standard URI filter
        self.standard_uris = {
            "www.w3.org/2002/07/owl",
            "www.w3.org/2000/01/rdf-schema",
            "www.w3.org/1999/02/22-rdf-syntax",
        }

    def detect_ontology_mode(self, ontology_text: str) -> Tuple[str, Optional[str]]:
        """
        Detect whether ontology uses prefixed terms (like ns1:) or full URIs
        Returns: (mode, target_namespace/prefix)
        """
        # Check for ns1: prefixed terms first (original format)
        ns1_matches = self.ns1_pattern.findall(ontology_text)
        if len(ns1_matches) > 5:  # Threshold to confirm ns1 usage
            print(f"DEBUG: Detected ns1: prefix mode with {len(ns1_matches)} terms")
            return "prefix", "ns1"

        # Check for other prefixed terms
        prefix_matches = self.prefixed_term_pattern.findall(ontology_text)
        prefix_counts = defaultdict(int)
        for prefix, local_name in prefix_matches:
            if prefix not in [
                "rdf",
                "rdfs",
                "owl",
                "xsd",
                "xml",
            ]:  # Skip standard prefixes
                prefix_counts[prefix] += 1

        if prefix_counts:
            most_common_prefix = max(prefix_counts.keys(), key=prefix_counts.get)
            if prefix_counts[most_common_prefix] > 5:
                print(
                    f"DEBUG: Detected prefix mode with prefix '{most_common_prefix}' ({prefix_counts[most_common_prefix]} terms)"
                )
                return "prefix", most_common_prefix

        # Check for full URIs
        uri_matches = self.uri_pattern.findall(ontology_text)
        namespace_counts = defaultdict(int)

        for full_uri, local_name in uri_matches:
            if not any(std in full_uri for std in self.standard_uris):
                namespace_counts[full_uri] += 1

        if namespace_counts:
            target_namespace = max(namespace_counts.keys(), key=namespace_counts.get)
            print(f"DEBUG: Detected URI mode with namespace: {target_namespace}")
            return "uri", target_namespace

        print("WARNING: Could not detect ontology mode!")
        return "unknown", None

    def extract_ontology_elements(self, ontology_text: str) -> Dict[str, Set[str]]:
        """Enhanced extraction with better categorization logic and deduplication"""
        elements = {
            "classes": set(),
            "object_properties": set(),
            "data_properties": set(),
            "individuals": set(),
        }

        # Detect the ontology mode and target namespace/prefix
        self.namespace_mode, self.target_namespace = self.detect_ontology_mode(
            ontology_text
        )

        if not self.target_namespace:
            print("WARNING: No target namespace/prefix detected!")
            return elements

        print(
            f"DEBUG: Processing in {self.namespace_mode} mode with target: {self.target_namespace}"
        )

        # Step 1: Extract explicit type declarations with high confidence
        if self.namespace_mode == "uri":
            self._extract_uri_explicit_types(ontology_text, elements)
        elif self.namespace_mode == "prefix":
            self._extract_prefix_explicit_types(ontology_text, elements)

        # Step 2: Extract remaining terms and categorize them with improved logic
        all_target_terms = self._extract_all_target_terms(ontology_text)
        existing_terms = (
            elements["classes"]
            | elements["object_properties"]
            | elements["data_properties"]
            | elements["individuals"]
        )

        missed_terms = all_target_terms - existing_terms
        print(f"DEBUG: Found {len(missed_terms)} additional terms to categorize")

        # Step 3: Categorize missed terms using improved heuristics
        for term in missed_terms:
            # Skip standard terms
            if term.lower() in ["class", "property", "type", "thing"]:
                continue

            category = self._categorize_term_improved(term, ontology_text)
            elements[category].add(term)

        print(
            f"DEBUG: Final counts - Classes: {len(elements['classes'])}, "
            f"Object Properties: {len(elements['object_properties'])}, "
            f"Data Properties: {len(elements['data_properties'])}, "
            f"Individuals: {len(elements['individuals'])}"
        )

        return elements

    def _extract_uri_explicit_types(
        self, ontology_text: str, elements: Dict[str, Set[str]]
    ):
        """Extract explicitly declared types from URI format"""
        # Classes
        class_matches = self.class_type_pattern.findall(ontology_text)
        for full_uri, local_name in class_matches:
            if full_uri == self.target_namespace:
                elements["classes"].add(local_name)

        # Object Properties
        obj_prop_matches = self.obj_prop_type_pattern.findall(ontology_text)
        for full_uri, local_name in obj_prop_matches:
            if full_uri == self.target_namespace:
                elements["object_properties"].add(local_name)

        # Data Properties
        data_prop_matches = self.data_prop_type_pattern.findall(ontology_text)
        for full_uri, local_name in data_prop_matches:
            if full_uri == self.target_namespace:
                elements["data_properties"].add(local_name)

        # Individuals
        individual_matches = self.individual_type_pattern.findall(ontology_text)
        for full_uri, local_name in individual_matches:
            if full_uri == self.target_namespace:
                elements["individuals"].add(local_name)

    def _extract_prefix_explicit_types(
        self, ontology_text: str, elements: Dict[str, Set[str]]
    ):
        """Extract explicitly declared types from prefix format"""
        # Classes
        class_matches = self.prefix_class_pattern.findall(ontology_text)
        for prefix, local_name in class_matches:
            if prefix == self.target_namespace:
                elements["classes"].add(local_name)

        # Object Properties
        obj_prop_matches = self.prefix_obj_prop_pattern.findall(ontology_text)
        for prefix, local_name in obj_prop_matches:
            if prefix == self.target_namespace:
                elements["object_properties"].add(local_name)

        # Data Properties
        data_prop_matches = self.prefix_data_prop_pattern.findall(ontology_text)
        for prefix, local_name in data_prop_matches:
            if prefix == self.target_namespace:
                elements["data_properties"].add(local_name)

        # Individuals
        individual_matches = self.prefix_individual_pattern.findall(ontology_text)
        for prefix, local_name in individual_matches:
            if prefix == self.target_namespace:
                elements["individuals"].add(local_name)

    def _extract_all_target_terms(self, ontology_text: str) -> Set[str]:
        """Extract ALL terms from the target namespace/prefix, normalized to local names"""
        target_terms = set()

        if self.namespace_mode == "prefix":
            # Extract prefixed terms
            if self.target_namespace == "ns1":
                matches = self.ns1_pattern.findall(ontology_text)
                target_terms = set(matches)
            else:
                pattern = re.compile(
                    rf"\b{re.escape(self.target_namespace)}:([A-Za-z][A-Za-z0-9_]*)\b"
                )
                matches = pattern.findall(ontology_text)
                target_terms = set(matches)

        elif self.namespace_mode == "uri":
            # Extract from full URIs
            uri_matches = self.uri_pattern.findall(ontology_text)
            for full_uri, local_name in uri_matches:
                if full_uri == self.target_namespace:
                    target_terms.add(local_name)

        return target_terms

    def _categorize_term_improved(self, local_name: str, ontology_text: str) -> str:
        """Improved categorization logic with better heuristics"""

        # 1. Check for explicit structural patterns in the ontology

        # Build search patterns based on the detected mode
        if self.namespace_mode == "uri":
            term_pattern = (
                f"<{re.escape(self.target_namespace)}#{re.escape(local_name)}>"
            )
        else:
            term_pattern = f"{re.escape(self.target_namespace)}:{re.escape(local_name)}"

        # Check if it appears as a subject in rdfs:subClassOf (strong class indicator)
        if re.search(rf"\b{re.escape(term_pattern)}\s+rdfs:subClassOf", ontology_text):
            return "classes"

        # Check if it appears as object in rdfs:subClassOf (strong class indicator)
        if re.search(rf"rdfs:subClassOf\s+{re.escape(term_pattern)}\b", ontology_text):
            return "classes"

        # Check if it has rdfs:domain or rdfs:range (strong property indicator)
        if re.search(
            rf"\b{re.escape(term_pattern)}\s+rdfs:(?:domain|range)", ontology_text
        ):
            return "object_properties"

        # Check if it appears in property position in triples (subject property object)
        property_position_pattern = rf"\w+\s+{re.escape(term_pattern)}\s+\w+"
        if re.search(property_position_pattern, ontology_text):
            return "object_properties"

        # Check if it appears as object of rdf:type with a class (individual indicator)
        individual_pattern = (
            rf"\b{re.escape(term_pattern)}\s+(?:rdf:type|a)\s+(?:\w+:|<[^>]*#)[A-Z]"
        )
        if re.search(individual_pattern, ontology_text):
            return "individuals"

        # 2. Apply naming convention heuristics

        # Individual naming patterns (high confidence)
        individual_patterns = [
            r"^[UPC][0-9]",  # U0, P155, C123, etc.
            r"^[A-Z][0-9]+$",  # Single letter followed by numbers
            r"[0-9]+$",  # ends with numbers only
            r"_[0-9]+$",  # ends with underscore followed by numbers
            r"^Individual\d+$",  # Individual123 pattern
        ]

        for pattern in individual_patterns:
            if re.search(pattern, local_name):
                return "individuals"

        # Property naming patterns (medium confidence)
        property_patterns = [
            r"^(has|is|can|should|get|set)",  # verb prefixes
            r"(Of|For|By|To|From|With)$",  # preposition suffixes
            r"^(advises|enrolls|teaches|offer|takes)",  # action verbs
            r"^[a-z]",  # starts with lowercase (common property convention)
        ]

        for pattern in property_patterns:
            if re.search(pattern, local_name, re.IGNORECASE):
                return "object_properties"

        # 3. Apply final classification based on capitalization and context

        # If starts with uppercase and doesn't match individual patterns, likely a class
        if local_name[0].isupper():
            return "classes"

        # Default fallback for lowercase
        return "object_properties"

    def create_abstraction_mappings(
        self, elements: Dict[str, Set[str]]
    ) -> Dict[str, Dict[str, str]]:
        """Create abstract mappings for all ontology elements"""
        mappings = {
            "classes": {},
            "object_properties": {},
            "data_properties": {},
            "individuals": {},
        }

        # Sort elements for consistent ordering
        for category in mappings.keys():
            sorted_elements = sorted(elements[category])

            if category == "classes":
                for i, local_name in enumerate(sorted_elements, 1):
                    mappings[category][local_name] = (
                        f"<http://www.example.com/abstracted.owl#Class{i}>"
                    )

            elif category == "object_properties":
                for i, local_name in enumerate(sorted_elements, 1):
                    mappings[category][local_name] = (
                        f"<http://www.example.com/abstracted.owl#Property{i}>"
                    )

            elif category == "data_properties":
                for i, local_name in enumerate(sorted_elements, 1):
                    mappings[category][local_name] = (
                        f"<http://www.example.com/abstracted.owl#DataProperty{i}>"
                    )

            elif category == "individuals":
                for i, local_name in enumerate(sorted_elements, 1):
                    mappings[category][local_name] = (
                        f"<http://www.example.com/abstracted.owl#Individual{i}>"
                    )

        return mappings

    def abstract_ontology_text(
        self, ontology_text: str, mappings: Dict[str, str]
    ) -> str:
        """Apply abstraction mappings to ontology text"""
        result = ontology_text

        print(f"DEBUG: Applying {len(mappings)} mappings to ontology")
        print(
            f"DEBUG: Detected mode: {self.namespace_mode}, target: {self.target_namespace}"
        )

        if self.namespace_mode == "uri":
            result = self._abstract_uri_mode(result, mappings)
        elif self.namespace_mode == "prefix":
            result = self._abstract_prefix_mode(result, mappings)

        return result

    def _abstract_uri_mode(self, text: str, mappings: Dict[str, str]) -> str:
        """Handle abstraction for URI mode ontologies"""
        result = text
        replacements_made = 0

        # Replace URIs in angle brackets
        def replace_uri(match):
            nonlocal replacements_made
            full_uri, local_name = match.groups()
            if full_uri == self.target_namespace and local_name in mappings:
                replacements_made += 1
                return mappings[local_name]
            return match.group(0)

        result = self.uri_pattern.sub(replace_uri, result)

        # Replace in comments
        comment_pattern = re.compile(
            rf"(###\s+{re.escape(self.target_namespace)}#)([^\s\n]+)"
        )

        def replace_comment(match):
            nonlocal replacements_made
            prefix = match.group(1)
            local_name = match.group(2)
            if local_name in mappings:
                replacements_made += 1
                abstract_uri = mappings[local_name]
                # Extract the local name from the abstract URI
                abstract_local = abstract_uri.split("#")[1].rstrip(">")
                return f"###  http://www.example.com/abstracted.owl#{abstract_local}"
            return match.group(0)

        result = comment_pattern.sub(replace_comment, result)

        print(f"DEBUG: Made {replacements_made} URI replacements")
        return result

    def _abstract_prefix_mode(self, text: str, mappings: Dict[str, str]) -> str:
        """Handle abstraction for prefix mode ontologies"""
        result = text
        total_replacements = 0

        # Sort mappings by length (longest first) to avoid partial replacements
        sorted_mappings = sorted(
            mappings.items(), key=lambda x: len(x[0]), reverse=True
        )

        for local_name, abstract_uri in sorted_mappings:
            # Create the full prefixed term
            prefixed_term = f"{self.target_namespace}:{local_name}"

            if prefixed_term in result:
                # Use word boundaries to ensure exact matches
                pattern = r"\b" + re.escape(prefixed_term) + r"\b"
                matches = re.findall(pattern, result)
                if matches:
                    result = re.sub(pattern, abstract_uri, result)
                    print(
                        f"DEBUG: Replaced {len(matches)} occurrences of {prefixed_term} -> {abstract_uri}"
                    )
                    total_replacements += len(matches)

        print(f"DEBUG: Made {total_replacements} total prefix replacements")
        return result


def process_file_worker(args):
    """Process a single file - worker function for parallel processing"""
    ttl_file, flat_mappings = args

    try:
        with open(ttl_file, "r", encoding="utf-8") as f:
            ontology_text = f.read()

        # Create fresh abstractor for this file
        file_abstractor = OntologyAbstractor()
        file_mode, file_target = file_abstractor.detect_ontology_mode(ontology_text)
        file_abstractor.namespace_mode = file_mode
        file_abstractor.target_namespace = file_target

        print(
            f"DEBUG: Processing {ttl_file.name} in {file_mode} mode with target {file_target}"
        )

        abstracted_text = file_abstractor.abstract_ontology_text(
            ontology_text, flat_mappings
        )
        return (ttl_file, abstracted_text, None)
    except Exception as e:
        return (ttl_file, None, str(e))


def process_ontology_abstraction(ontology_dir: str, output_dir: str):
    """Main processing function for ontology abstraction"""

    abstractor = OntologyAbstractor()

    # Create output directories
    Path(output_dir).mkdir(parents=True, exist_ok=True)
    abstracted_ontology_dir = Path(output_dir) / "abstracted_ontologies"
    abstracted_ontology_dir.mkdir(exist_ok=True)

    print("=== Ontology Abstraction Tool ===")

    # Step 1: Collect all elements from ontologies using normalized local names
    print("Step 1: Analyzing all ontologies...")
    all_elements = {
        "classes": set(),
        "object_properties": set(),
        "data_properties": set(),
        "individuals": set(),
    }

    # Analyze ontology files
    ttl_files = list(Path(ontology_dir).glob("**/*.ttl"))
    print(f"Found {len(ttl_files)} TTL files to process")

    processed_count = 0
    for ttl_file in ttl_files:
        try:
            with open(ttl_file, "r", encoding="utf-8") as f:
                ontology_text = f.read()

            # Create a fresh instance for each file
            file_abstractor = OntologyAbstractor()
            elements = file_abstractor.extract_ontology_elements(ontology_text)

            # Add elements to global collection (elements are already local names)
            for category in all_elements:
                all_elements[category].update(elements[category])

            processed_count += 1

        except Exception as e:
            print(f"Error processing {ttl_file}: {e}")
            continue

        if processed_count % 50 == 0:
            print(f"  Analyzed {processed_count}/{len(ttl_files)} ontologies")

    print(f"  Completed analysis of {processed_count} ontologies")

    # Step 2: Remove overlaps - prioritize explicit declarations
    print("Step 2: Removing duplicates and conflicting categorizations...")

    # Remove any entity that appears in multiple categories (prioritize classes > properties > individuals)
    all_entities = set()
    for category in ["classes", "object_properties", "data_properties", "individuals"]:
        all_entities.update(all_elements[category])

    # Find entities that appear in multiple categories
    overlapping_entities = set()
    for entity in all_entities:
        categories_found = []
        for category in all_elements:
            if entity in all_elements[category]:
                categories_found.append(category)
        if len(categories_found) > 1:
            overlapping_entities.add(entity)
            print(f"  Overlap found: {entity} in {categories_found}")

            # Remove from all categories first
            for category in all_elements:
                all_elements[category].discard(entity)

            # Re-add to highest priority category
            if "classes" in categories_found:
                all_elements["classes"].add(entity)
            elif "object_properties" in categories_found:
                all_elements["object_properties"].add(entity)
            elif "data_properties" in categories_found:
                all_elements["data_properties"].add(entity)
            else:
                all_elements["individuals"].add(entity)

    print(f"  Resolved {len(overlapping_entities)} overlapping entities")

    # Step 3: Create global mappings
    print("Step 3: Creating consistent abstraction mappings...")
    global_mappings = abstractor.create_abstraction_mappings(all_elements)

    # Flatten mappings for easier use
    flat_mappings = {}
    for category_mappings in global_mappings.values():
        flat_mappings.update(category_mappings)

    print(f"DEBUG: Created {len(flat_mappings)} total mappings")

    # Save mappings
    mappings_file = Path(output_dir) / "abstraction_mappings.txt"
    with open(mappings_file, "w", encoding="utf-8") as f:
        f.write("=== ONTOLOGY ABSTRACTION MAPPINGS ===\n\n")
        for category, mapping in global_mappings.items():
            f.write(f"=== {category.upper().replace('_', ' ')} ===\n")
            for original, abstract in sorted(mapping.items()):
                f.write(f"{original} -> {abstract}\n")
            f.write("\n")

    print(f"  Found {len(all_elements['classes'])} classes")
    print(f"  Found {len(all_elements['object_properties'])} object properties")
    print(f"  Found {len(all_elements['data_properties'])} data properties")
    print(f"  Found {len(all_elements['individuals'])} individuals")

    # Step 4: Abstract ontologies using parallel processing
    print("Step 4: Abstracting ontologies...")

    # Use parallel processing for abstraction
    max_workers = min(multiprocessing.cpu_count(), 8)
    print(f"Using {max_workers} parallel workers")

    # Prepare arguments for parallel processing
    file_args = [(ttl_file, flat_mappings) for ttl_file in ttl_files]

    # Process files in parallel
    with concurrent.futures.ProcessPoolExecutor(max_workers=max_workers) as executor:
        future_to_file = {
            executor.submit(process_file_worker, arg): arg[0] for arg in file_args
        }

        processed = 0
        for future in concurrent.futures.as_completed(future_to_file):
            ttl_file, abstracted_text, error = future.result()

            if error:
                print(f"Error processing {ttl_file}: {error}")
                continue

            # Save the abstracted file
            output_file = abstracted_ontology_dir / ttl_file.name
            with open(output_file, "w", encoding="utf-8") as f:
                f.write(abstracted_text)

            processed += 1
            if processed % 50 == 0:
                print(f"  Processed {processed}/{len(file_args)} ontologies")

    print(f"  Completed processing {len(file_args)} ontologies")

    print("\n=== Ontology Abstraction Complete! ===")
    print(f"📁 Abstracted ontologies: {abstracted_ontology_dir}")
    print(f"🗂️ Mappings reference: {mappings_file}")

    return {"ontologies_dir": abstracted_ontology_dir, "mappings_file": mappings_file}


def main():
    parser = argparse.ArgumentParser(description="Ontology Abstraction Tool")
    parser.add_argument(
        "--ontology_dir", required=True, help="Directory containing TTL ontology files"
    )
    parser.add_argument(
        "--output_dir", required=True, help="Output directory for abstracted files"
    )

    args = parser.parse_args()

    # Process the data
    results = process_ontology_abstraction(
        ontology_dir=args.ontology_dir, output_dir=args.output_dir
    )

    print(f"\nAll abstracted files saved to: {args.output_dir}")


if __name__ == "__main__":
    main()
