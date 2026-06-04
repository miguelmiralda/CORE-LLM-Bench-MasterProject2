# Import necessary libraries
import rdflib
from rdflib import Graph, URIRef, Literal, BNode
from rdflib.namespace import RDF, RDFS, OWL
from pathlib import Path
import os
import re
import json
import inflect
import gc
import psutil
import time
from pathlib import Path

# SimpleNLG imports (optional)
try:
    from simplenlg.framework import NLGFactory, CoordinatedPhraseElement
    from simplenlg.lexicon import Lexicon
    from simplenlg.realiser.english import Realiser
    from simplenlg.phrasespec import SPhraseSpec
    from simplenlg.features import Feature, Tense, NumberAgreement

    SIMPLENLG_AVAILABLE = True
    print("âœ… SimpleNLG available")
except ImportError:
    print("âš ï¸ SimpleNLG not available. Using basic text generation.")
    SIMPLENLG_AVAILABLE = False

# Navigate to project root
try:
    script_dir = Path(__file__).resolve().parent
    project_root = script_dir.parent.parent
    os.chdir(project_root)
except NameError:
    print(
        "Could not dynamically set project root. Assuming current working directory is correct."
    )
    pass

print(f"Working directory set to: {os.getcwd()}")

# Initialize SimpleNLG if available
if SIMPLENLG_AVAILABLE:
    lexicon = Lexicon.getDefaultLexicon()
    nlg_factory = NLGFactory(lexicon)
    realiser = Realiser(lexicon)
    print("âœ… SimpleNLG initialized")

p = inflect.engine()


class DomainIndependentVerbalizer:
    """Core verbalizer that works with any domain by using structural patterns only"""

    def __init__(self):
        # Pure linguistic patterns based on property structure only
        self.structural_patterns = {
            "has_prefix": self._has_pattern,
            "is_of_suffix": self._is_of_pattern,
            "inverse_property": self._inverse_pattern,
            "symmetric_property": self._symmetric_pattern,
            "transitive_property": self._transitive_pattern,
            "functional_property": self._functional_pattern,
            "default": self._default_pattern,
        }

    def detect_linguistic_pattern(self, property_name, property_characteristics):
        """Detect pattern based purely on linguistic structure, not semantics"""

        # Check OWL property characteristics first (most reliable)
        if "functional" in property_characteristics:
            return "functional_property"
        if "symmetric" in property_characteristics:
            return "symmetric_property"
        if "transitive" in property_characteristics:
            return "transitive_property"

        # Then check pure linguistic patterns
        clean_name = self._clean_property_name(property_name)

        if clean_name.startswith("has"):
            return "has_prefix"
        elif clean_name.startswith("is") and clean_name.endswith("of"):
            return "is_of_suffix"
        else:
            return "default"

    def _clean_property_name(self, prop_name):
        """Clean property name to basic linguistic components"""
        # Remove namespace prefixes
        if "#" in prop_name:
            prop_name = prop_name.split("#")[-1]
        elif "/" in prop_name:
            prop_name = prop_name.split("/")[-1]

        # Convert camelCase to space-separated
        spaced = re.sub(r"([a-z])([A-Z])", r"\1 \2", prop_name)

        # Clean up the result
        cleaned = spaced.lower().strip()

        # Fix common issues
        cleaned = re.sub(r"\s+", " ", cleaned)  # Remove extra spaces

        return cleaned

    def _has_pattern(self, subject, prop_name, obj):
        """Pattern: hasX -> 'subject has X object'"""
        clean_prop = self._clean_property_name(prop_name)

        # More careful removal of 'has' - only from the beginning
        relation = clean_prop
        if relation.startswith("has "):
            relation = relation[4:]  # Remove 'has '
        elif relation.startswith("has"):
            relation = relation[3:]  # Remove 'has'

        relation = relation.strip()

        if relation:
            return f"{subject} has {relation} {obj}"
        return f"{subject} has {obj}"

    def _is_of_pattern(self, subject, prop_name, obj):
        """Pattern: isXOf -> 'subject is X of object'"""
        clean_prop = self._clean_property_name(prop_name)

        # More careful removal of 'is' and 'of' - only from start/end
        relation = clean_prop

        # Remove 'is' only from the beginning
        if relation.startswith("is "):
            relation = relation[3:]  # Remove 'is '
        elif relation.startswith("is"):
            relation = relation[2:]  # Remove 'is'

        # Remove 'of' only from the end
        if relation.endswith(" of"):
            relation = relation[:-3]  # Remove ' of'
        elif relation.endswith("of"):
            relation = relation[:-2]  # Remove 'of'

        relation = relation.strip()

        if relation:
            return f"{subject} is {relation} of {obj}"
        return f"{subject} is related to {obj}"

    def _inverse_pattern(self, subject, prop_name, obj):
        """For inverse properties, swap subject/object linguistically"""
        base_relation = self._extract_base_relation(prop_name)
        return f"{obj} {base_relation} {subject}"

    def _symmetric_pattern(self, subject, prop_name, obj):
        """Symmetric properties: both directions implied"""
        base_relation = self._extract_base_relation(prop_name)
        return f"{subject} and {obj} are mutually {base_relation}"

    def _transitive_pattern(self, subject, prop_name, obj):
        """Transitive properties: mention transitivity"""
        base_relation = self._extract_base_relation(prop_name)
        return f"{subject} {base_relation} {obj}"

    def _functional_pattern(self, subject, prop_name, obj):
        """Functional properties: unique relationship"""
        base_relation = self._extract_base_relation(prop_name)
        return f"{subject} has exactly one {base_relation} {obj}"

    def _default_pattern(self, subject, prop_name, obj):
        """Default: use property name as-is with cleaning"""
        clean_prop = self._clean_property_name(prop_name)

        # Try to make it into a reasonable verb phrase
        if clean_prop.startswith("has"):
            # Use the careful has pattern
            return self._has_pattern(subject, prop_name, obj)
        elif clean_prop.startswith("is") and clean_prop.endswith("of"):
            # Use the careful is_of pattern
            return self._is_of_pattern(subject, prop_name, obj)
        else:
            # Use the cleaned property name as a verb phrase
            return f"{subject} {clean_prop} {obj}"

    def _extract_base_relation(self, prop_name):
        """Extract the core relation from any property name"""
        clean = self._clean_property_name(prop_name)

        # Use the same careful approach for extraction
        relation = clean
        if relation.startswith("has "):
            relation = relation[4:]
        elif relation.startswith("has"):
            relation = relation[3:]
        elif relation.startswith("is "):
            relation = relation[3:]
        elif relation.startswith("is"):
            relation = relation[2:]

        if relation.endswith(" of"):
            relation = relation[:-3]
        elif relation.endswith("of"):
            relation = relation[:-2]

        relation = relation.strip()
        return relation if relation else "relates to"


def clean_entity_name(name):
    """Clean entity names to be more human-readable for any domain"""
    if not name:
        return ""

    # Remove common technical suffixes
    name = re.sub(r"_\d{4}$", "", name)  # Remove years like _1885
    name = re.sub(r"_\d+$", "", name)  # Remove any trailing numbers
    name = re.sub(r"_v\d+$", "", name)  # Remove version numbers like _v1
    name = re.sub(r"_\w{2,3}$", "", name)  # Remove short suffixes like _en, _us

    # Convert camelCase to Title Case
    name = re.sub(r"([a-z])([A-Z])", r"\1 \2", name)

    # Replace underscores and hyphens with spaces
    name = name.replace("_", " ").replace("-", " ")

    # Remove common prefixes
    prefixes_to_remove = ["owl:", "rdf:", "rdfs:", "xsd:", "foaf:", "dc:", "dct:"]
    for prefix in prefixes_to_remove:
        if name.lower().startswith(prefix):
            name = name[len(prefix) :]

    # Capitalize properly and clean up spaces
    words = [word.capitalize() for word in name.split() if word]
    return " ".join(words)


def get_nice_label(g, entity):
    """Get a nice label for an entity from any domain"""
    # Priority order for labels
    label_properties = [
        RDFS.label,
        URIRef("http://www.w3.org/2004/02/skos/core#prefLabel"),
        URIRef("http://purl.org/dc/elements/1.1/title"),
        URIRef("http://xmlns.com/foaf/0.1/name"),
        URIRef("http://schema.org/name"),
    ]

    # Try to find a label using common label properties
    for label_prop in label_properties:
        label = g.value(entity, label_prop)
        if label:
            return clean_entity_name(str(label))

    # If no label found, extract from URI
    if isinstance(entity, URIRef):
        uri_str = str(entity)
        if "#" in uri_str:
            name = uri_str.split("#")[-1]
        elif "/" in uri_str:
            name = uri_str.split("/")[-1]
        else:
            name = uri_str
        return clean_entity_name(name)

    return None


def create_simple_sentence(subject, verb, object_val, is_plural_subject=False):
    """Create a grammatically correct sentence using SimpleNLG or fallback"""
    if not SIMPLENLG_AVAILABLE:
        return f"{subject} {verb} {object_val}."

    try:
        # Create sentence
        sentence = nlg_factory.createClause()
        sentence.setSubject(subject)
        sentence.setVerb(verb)
        if object_val:
            sentence.setObject(object_val)

        # Set number agreement
        if is_plural_subject:
            sentence.setFeature(Feature.NUMBER, NumberAgreement.PLURAL)

        # Realize the sentence
        output = realiser.realiseSentence(sentence)
        return output.strip()

    except Exception as e:
        # Fallback to simple concatenation
        return f"{subject} {verb} {object_val}."


def create_list_sentence(subject, verb, object_list):
    """Create a sentence with a coordinated list of objects"""
    if not SIMPLENLG_AVAILABLE or not object_list:
        if len(object_list) == 1:
            return f"{subject} {verb} {object_list[0]}."
        elif len(object_list) == 2:
            return f"{subject} {verb} {object_list[0]} and {object_list[1]}."
        else:
            return f"{subject} {verb} {', '.join(object_list[:-1])}, and {object_list[-1]}."

    try:
        # Create coordinated phrase for multiple objects
        if len(object_list) > 1:
            coord_phrase = nlg_factory.createCoordinatedPhrase()
            for obj in object_list:
                coord_phrase.addCoordinate(obj)

            sentence = nlg_factory.createClause()
            sentence.setSubject(subject)
            sentence.setVerb(verb)
            sentence.setObject(coord_phrase)
        else:
            sentence = nlg_factory.createClause()
            sentence.setSubject(subject)
            sentence.setVerb(verb)
            sentence.setObject(object_list[0])

        output = realiser.realiseSentence(sentence)
        return output.strip()

    except Exception as e:
        # Fallback
        if len(object_list) == 1:
            return f"{subject} {verb} {object_list[0]}."
        return f"{subject} {verb} {', '.join(object_list[:-1])} and {object_list[-1]}."


def _parse_restriction(g, restriction_node):
    """Helper function to parse an owl:Restriction and return a human-readable string."""
    on_prop_uri = g.value(restriction_node, OWL.onProperty)
    if not on_prop_uri:
        return None

    prop_name = get_nice_label(g, on_prop_uri)
    if not prop_name:
        return None

    # someValuesFrom
    some_values_from = g.value(restriction_node, OWL.someValuesFrom)
    if some_values_from:
        class_name = get_nice_label(g, some_values_from)
        if class_name:
            return f"that has some '{prop_name}' relationship with an instance of {class_name}"

    # allValuesFrom
    all_values_from = g.value(restriction_node, OWL.allValuesFrom)
    if all_values_from:
        class_name = get_nice_label(g, all_values_from)
        if class_name:
            return f"that only has '{prop_name}' relationships with instances of {class_name}"

    # Cardinality (simplified)
    cardinality = g.value(restriction_node, OWL.qualifiedCardinality)
    if cardinality:
        on_class = g.value(restriction_node, OWL.onClass)
        class_name = get_nice_label(g, on_class)
        if class_name:
            return f"that has exactly {cardinality} '{prop_name}' relationship(s) with an instance of {class_name}"

    return None  # Fallback for other restriction types


def describe_class_with_domain_independence(
    g,
    cls,
    classes,
    subclass_relations,
    equivalent_class_relations,
    obj_properties,
    property_domains,
    property_ranges,
):
    """Generate natural language description of a class without domain assumptions"""
    descriptions = []
    cls_name = get_nice_label(g, cls)

    if not cls_name:
        return ""

    # Subclass relationships (pure structural)
    parents = [o for s, o in subclass_relations if s == cls]
    if parents:
        parent_names = [
            get_nice_label(g, parent) for parent in parents if get_nice_label(g, parent)
        ]
        if parent_names:
            if len(parent_names) == 1:
                desc = create_simple_sentence(cls_name, "is a type of", parent_names[0])
            else:
                desc = create_list_sentence(cls_name, "is a type of", parent_names)
            descriptions.append(desc)

    # --- START MODIFICATION ---

    # Equivalent and Complex Classes (Restrictions)
    equivalents = [o for s, o in equivalent_class_relations if s == cls]
    if equivalents:
        equiv_names = []
        for eq in equivalents:
            if isinstance(eq, URIRef):
                eq_name = get_nice_label(g, eq)
                if eq_name:
                    equiv_names.append(eq_name)
            elif isinstance(eq, BNode):  # This is a complex class definition
                restriction_desc = _parse_restriction(g, eq)
                if restriction_desc:
                    descriptions.append(
                        f"{cls_name} is defined as a class {restriction_desc}."
                    )

        if equiv_names:
            if len(equiv_names) == 1:
                desc = create_simple_sentence(
                    cls_name, "is equivalent to", equiv_names[0]
                )
            else:
                desc = create_list_sentence(cls_name, "is equivalent to", equiv_names)
            descriptions.append(desc)

    # Disjoint Classes
    disjoint_classes = []
    for s, p, o in g.triples((cls, OWL.disjointWith, None)):
        disjoint_name = get_nice_label(g, o)
        if disjoint_name:
            disjoint_classes.append(disjoint_name)

    if disjoint_classes:
        disjoint_list = " and ".join(disjoint_classes)
        descriptions.append(f"{cls_name} is disjoint with {disjoint_list}.")

    # --- END MODIFICATION ---

    # Domain properties (structural description only)
    domain_props = [
        prop for prop in obj_properties if cls in property_domains.get(prop, set())
    ]
    if domain_props and len(domain_props) <= 3:
        prop_names = [
            get_nice_label(g, prop) for prop in domain_props if get_nice_label(g, prop)
        ]
        if prop_names:
            if len(prop_names) == 1:
                desc = (
                    f"Instances of {cls_name} can have {prop_names[0]} relationships."
                )
            else:
                prop_text = ", ".join(prop_names[:-1]) + f" and {prop_names[-1]}"
                desc = f"Instances of {cls_name} can have {prop_text} relationships."
            descriptions.append(desc)

    if not descriptions:
        desc = create_simple_sentence(cls_name, "is", "a class in this ontology")
        descriptions.append(desc)

    return " ".join(descriptions)


def parse_rdf_list(g, node, visited=None):
    """Recursively parses an RDF list (collection) and returns a Python list of its items."""
    if visited is None:
        visited = set()
    if node in visited:  # Avoid recursion loops
        return []
    visited.add(node)

    items = []
    current = node
    while current and current != RDF.nil:
        first = g.value(current, RDF.first)
        if first:
            items.append(first)
        current = g.value(current, RDF.rest)
    return items


def describe_property_with_domain_independence(
    g, prop, obj_properties, property_domains, property_ranges
):
    """Generate natural language description of a property without domain assumptions"""
    descriptions = []
    prop_name = get_nice_label(g, prop)

    if not prop_name:
        return ""

    # Basic description
    desc = f"{prop_name} is a relationship property."
    descriptions.append(desc)

    # --- ADD SUBPROPERTY HANDLING HERE ---

    # SubPropertyOf relationships
    super_properties = []
    for s, p, o in g.triples((prop, RDFS.subPropertyOf, None)):
        super_prop_name = get_nice_label(g, o)
        if super_prop_name:
            super_properties.append(super_prop_name)

    if super_properties:
        if len(super_properties) == 1:
            descriptions.append(
                f"This property is a subproperty of {super_properties[0]}."
            )
        else:
            super_list = " and ".join(super_properties)
            descriptions.append(f"This property is a subproperty of {super_list}.")

    # Sub-properties (properties that are subproperties of this one)
    sub_properties = []
    for s, p, o in g.triples((None, RDFS.subPropertyOf, prop)):
        sub_prop_name = get_nice_label(g, s)
        if sub_prop_name:
            sub_properties.append(sub_prop_name)

    if sub_properties:
        if len(sub_properties) == 1:
            descriptions.append(
                f"{sub_properties[0]} is a subproperty of this property."
            )
        else:
            sub_list = ", ".join(sub_properties[:-1]) + f" and {sub_properties[-1]}"
            descriptions.append(f"{sub_list} are subproperties of this property.")

    # --- END SUBPROPERTY HANDLING ---

    # Check property characteristics (formal OWL properties)
    characteristics = []
    for s, p, o in g.triples((prop, RDF.type, None)):
        if o == OWL.SymmetricProperty:
            characteristics.append("symmetric")
        elif o == OWL.TransitiveProperty:
            characteristics.append("transitive")
        elif o == OWL.FunctionalProperty:
            characteristics.append("functional")
        elif o == OWL.InverseFunctionalProperty:
            characteristics.append("inverse functional")
        elif o == OWL.AsymmetricProperty:
            characteristics.append("asymmetric")
        elif o == OWL.IrreflexiveProperty:
            characteristics.append("irreflexive")

    # Property Chain Axiom
    for s, p, o in g.triples((prop, OWL.propertyChainAxiom, None)):
        # The object 'o' is the head of an RDF list
        chain_list = parse_rdf_list(g, o)
        chain_names = [
            get_nice_label(g, item) for item in chain_list if get_nice_label(g, item)
        ]

        if len(chain_names) > 1:
            chain_text = " followed by ".join(chain_names)
            descriptions.append(
                f"This property can be inferred from the chain of relationships: {chain_text}."
            )

    # Add characteristics descriptions from the list
    if "functional" in characteristics:
        descriptions.append(
            "This property is functional (each entity can have at most one value)."
        )
    if "inverse functional" in characteristics:
        descriptions.append(
            "This property is inverse functional (each value can be related to at most one entity)."
        )
    if "symmetric" in characteristics:
        descriptions.append(
            "This property is symmetric (if A relates to B, then B relates to A)."
        )
    if "asymmetric" in characteristics:
        descriptions.append(
            "This property is asymmetric (if A relates to B, then B cannot relate to A)."
        )
    if "transitive" in characteristics:
        descriptions.append(
            "This property is transitive (it can form chains of relationships)."
        )
    if "irreflexive" in characteristics:
        descriptions.append(
            "This property is irreflexive (an entity cannot have this relationship with itself)."
        )

    # Check for inverse, equivalent, and disjoint properties separately

    # InverseOf
    for s, p, o in g.triples((prop, OWL.inverseOf, None)):
        inverse_prop_name = get_nice_label(g, o)
        if inverse_prop_name:
            descriptions.append(
                f"It is the inverse of the {inverse_prop_name} property."
            )

    # EquivalentProperty
    for s, p, o in g.triples((prop, OWL.equivalentProperty, None)):
        equiv_prop_name = get_nice_label(g, o)
        if equiv_prop_name:
            descriptions.append(f"This property is equivalent to {equiv_prop_name}.")

    # PropertyDisjointWith
    disjoint_props = []
    for s, p, o in g.triples((prop, OWL.propertyDisjointWith, None)):
        disjoint_prop_name = get_nice_label(g, o)
        if disjoint_prop_name:
            disjoint_props.append(disjoint_prop_name)
    if disjoint_props:
        disjoint_list = " and ".join(disjoint_props)
        descriptions.append(f"This property is disjoint with {disjoint_list}.")

    # --- END MODIFICATION ---

    # Domain and range information (no changes needed here)
    domains = property_domains.get(prop, set())
    ranges = property_ranges.get(prop, set())

    domain_names = [get_nice_label(g, d) for d in domains if get_nice_label(g, d)]
    range_names = [get_nice_label(g, r) for r in ranges if get_nice_label(g, r)]

    if domain_names and range_names:
        if len(domain_names) == 1 and len(range_names) == 1:
            desc = f"This property connects {domain_names[0]} to {range_names[0]}."
        else:
            domain_text = " or ".join(domain_names)
            range_text = " or ".join(range_names)
            desc = f"This property connects {domain_text} to {range_text}."
        descriptions.append(desc)

    return " ".join(descriptions)


def get_property_hierarchy(g, obj_properties):
    """Build a complete property hierarchy"""
    subprop_relations = []
    for s, p, o in g.triples((None, RDFS.subPropertyOf, None)):
        if s in obj_properties and o in obj_properties:
            subprop_relations.append((s, o))
    return subprop_relations


def detect_domain_type_structurally(g, individuals, classes, obj_properties):
    """Detect domain type based purely on structural patterns, not semantics"""
    # Count structural patterns rather than domain-specific terms
    pattern_counts = {
        "hierarchical": 0,  # lots of subclass relationships
        "relational": 0,  # lots of object properties
        "instance_heavy": 0,  # lots of individuals
        "simple": 0,  # basic structure
    }

    # Count subclass relationships
    subclass_count = 0
    for s, p, o in g.triples((None, RDFS.subClassOf, None)):
        subclass_count += 1

    # Count object properties
    obj_prop_count = len(obj_properties)

    # Count individuals
    individual_count = len(individuals)

    # Determine structural complexity
    if subclass_count > 10:
        return "hierarchical"
    elif obj_prop_count > 20:
        return "relational"
    elif individual_count > 50:
        return "instance_heavy"
    else:
        return "general"


def get_all_individuals(g, classes, obj_properties):
    """Get all individuals including those not explicitly declared"""
    individuals = set()

    # Explicitly declared individuals
    for s, p, o in g.triples((None, RDF.type, OWL.NamedIndividual)):
        if isinstance(s, URIRef):
            individuals.add(s)

    # Find individuals that are subjects or objects of object properties
    # but are not classes, properties, or other ontological constructs
    excluded_types = {
        OWL.Class,
        OWL.ObjectProperty,
        OWL.DatatypeProperty,
        OWL.AnnotationProperty,
        OWL.Ontology,
        RDFS.Class,
    }

    for prop in obj_properties:
        # Get all subjects and objects of this property
        for s, p, o in g.triples((None, prop, None)):
            if isinstance(s, URIRef):
                # Check if s is not a class/property
                s_types = set(g.objects(s, RDF.type))
                if not s_types.intersection(excluded_types):
                    individuals.add(s)

            if isinstance(o, URIRef):
                # Check if o is not a class/property
                o_types = set(g.objects(o, RDF.type))
                if not o_types.intersection(excluded_types):
                    individuals.add(o)

    # Also check for any URI that appears as subject of any triple
    # and has some class type (even if inferred)
    for s, p, o in g.triples((None, RDF.type, None)):
        if isinstance(s, URIRef) and o in classes:
            individuals.add(s)

    # Additional heuristic: any URIRef that's not a known class/property
    # and appears in object position of relevant triples
    all_uris = set()
    for s, p, o in g:
        if isinstance(s, URIRef):
            all_uris.add(s)
        if isinstance(o, URIRef):
            all_uris.add(o)

    # Filter out known ontological constructs
    ontological_uris = classes.union(obj_properties).union(
        {RDF.type, RDFS.subClassOf, OWL.equivalentClass, RDFS.domain, RDFS.range}
    )

    for uri in all_uris:
        if uri not in ontological_uris:
            # Check if it's used in meaningful relationships
            has_relationships = False
            for s, p, o in g.triples((uri, None, None)):
                if p in obj_properties:
                    has_relationships = True
                    break
            for s, p, o in g.triples((None, None, uri)):
                if p in obj_properties:
                    has_relationships = True
                    break

            if has_relationships:
                individuals.add(uri)

    return individuals


def describe_individual_with_domain_independence(
    g, ind, classes, obj_properties, all_individuals=None
):
    """Generate natural language description of an individual without domain assumptions"""
    descriptions = []
    ind_name = get_nice_label(g, ind)

    if not ind_name:
        return ""

    verbalizer = DomainIndependentVerbalizer()

    # Get types/classes
    types = []
    for s, p, o in g.triples((ind, RDF.type, None)):
        if o != OWL.NamedIndividual and o in classes:
            types.append(o)

    if types:
        type_names = [get_nice_label(g, t) for t in types if get_nice_label(g, t)]
        type_names = [name for name in type_names if name and isinstance(name, str)]
        if type_names:
            if len(type_names) == 1:
                desc = create_simple_sentence(
                    ind_name, "is", f"an instance of {type_names[0]}"
                )
            else:
                type_text = " and ".join([f"an instance of {t}" for t in type_names])
                desc = f"{ind_name} is {type_text}."
            descriptions.append(desc)

    # Collect and organize relationships to avoid duplicates and improve readability
    outgoing_relationships = {}
    incoming_relationships = {}
    property_characteristics = {}

    # Get ALL outgoing relationships (including to non-declared individuals)
    for s, p, o in g.triples((ind, None, None)):
        if p != RDF.type and p in obj_properties and isinstance(o, URIRef):
            prop_name = get_nice_label(g, p)
            obj_name = get_nice_label(g, o)

            # If obj_name is None, try to extract from URI
            if not obj_name:
                obj_name = clean_entity_name(str(o).split("#")[-1].split("/")[-1])

            if prop_name and obj_name:
                if prop_name not in outgoing_relationships:
                    outgoing_relationships[prop_name] = []
                outgoing_relationships[prop_name].append(obj_name)

                # Collect property characteristics
                if p not in property_characteristics:
                    property_characteristics[p] = []
                    for _, prop_type, _ in g.triples((p, RDF.type, None)):
                        if prop_type == OWL.SymmetricProperty:
                            property_characteristics[p].append("symmetric")
                        elif prop_type == OWL.TransitiveProperty:
                            property_characteristics[p].append("transitive")
                        elif prop_type == OWL.FunctionalProperty:
                            property_characteristics[p].append("functional")

    # Get ALL incoming relationships
    for s, p, o in g.triples((None, None, ind)):
        if isinstance(s, URIRef) and s != ind and p in obj_properties:
            subj_name = get_nice_label(g, s)

            # If subj_name is None, try to extract from URI
            if not subj_name:
                subj_name = clean_entity_name(str(s).split("#")[-1].split("/")[-1])

            prop_name = get_nice_label(g, p)
            if subj_name and prop_name:
                prop_key = f"incoming_{prop_name}"
                if prop_key not in incoming_relationships:
                    incoming_relationships[prop_key] = []
                incoming_relationships[prop_key].append(subj_name)

    # Generate sentences for outgoing relationships
    for prop_name, related_entities in outgoing_relationships.items():
        # Find the property URI for this prop_name
        prop_uri = None
        for s, p, o in g.triples((ind, None, None)):
            if get_nice_label(g, p) == prop_name:
                prop_uri = p
                break

        if prop_uri:
            chars = property_characteristics.get(prop_uri, [])
            pattern = verbalizer.detect_linguistic_pattern(str(prop_uri), chars)

            # Group multiple objects for the same property
            if len(related_entities) == 1:
                desc = verbalizer.structural_patterns[pattern](
                    ind_name, str(prop_uri), related_entities[0]
                )
                descriptions.append(desc + ".")
            else:
                # Create a single sentence with multiple objects
                objects_text = (
                    ", ".join(related_entities[:-1]) + f" and {related_entities[-1]}"
                )
                desc = verbalizer.structural_patterns[pattern](
                    ind_name, str(prop_uri), objects_text
                )
                descriptions.append(desc + ".")

    # Generate sentences for incoming relationships (more selective)
    for prop_key, related_entities in incoming_relationships.items():
        prop_name = prop_key.replace("incoming_", "")

        # Find the property URI
        prop_uri = None
        for s, p, o in g.triples((None, None, ind)):
            if get_nice_label(g, p) == prop_name:
                prop_uri = p
                break

        if prop_uri:
            chars = property_characteristics.get(prop_uri, [])

            # Only include incoming relationships if they're not already covered by outgoing
            # and if they add meaningful information
            if prop_name not in outgoing_relationships:
                if len(related_entities) == 1:
                    pattern = verbalizer.detect_linguistic_pattern(str(prop_uri), chars)
                    desc = verbalizer.structural_patterns[pattern](
                        related_entities[0], str(prop_uri), ind_name
                    )
                    descriptions.append(desc + ".")
                elif len(related_entities) <= 5:  # Show more incoming relationships
                    subject = (
                        ", ".join(related_entities[:-1])
                        + f" and {related_entities[-1]}"
                    )
                    desc = f"{subject} are related to {ind_name} through {prop_name}"
                    descriptions.append(desc + ".")

    if not descriptions:
        desc = create_simple_sentence(ind_name, "is", "an individual in this ontology")
        descriptions.append(desc)

    # Join descriptions with proper spacing
    return " ".join(descriptions)


def verbalize_ontology(ontology_file_path, output_dir):
    """Verbalize a single ontology file and save as JSON - domain independent"""
    print(f"Processing: {ontology_file_path}")

    g = Graph()
    try:
        g.parse(ontology_file_path)
        print(f"Successfully loaded ontology with {len(g)} triples")
    except Exception as e:
        print(f"Error loading ontology: {e}")
        return None

    try:
        # Extract ontology structure
        classes = set()
        for s, p, o in g.triples((None, RDF.type, OWL.Class)):
            if isinstance(s, URIRef):
                classes.add(s)

        obj_properties = set()
        for s, p, o in g.triples((None, RDF.type, OWL.ObjectProperty)):
            if isinstance(s, URIRef):
                obj_properties.add(s)

        data_properties = set()
        for s, p, o in g.triples((None, RDF.type, OWL.DatatypeProperty)):
            if isinstance(s, URIRef):
                data_properties.add(s)

        # Enhanced individual detection
        individuals = get_all_individuals(g, classes, obj_properties)

        print(f"Found {len(individuals)} individuals (including implicit ones)")

        # Detect domain type structurally
        domain_type = detect_domain_type_structurally(
            g, individuals, classes, obj_properties
        )
        print(f"Detected structural type: {domain_type}")

        # Get relationships
        subclass_relations = []
        for s, p, o in g.triples((None, RDFS.subClassOf, None)):
            if s in classes and o in classes:
                subclass_relations.append((s, o))

        equivalent_class_relations = []
        for s, p, o in g.triples((None, OWL.equivalentClass, None)):
            if s in classes:
                equivalent_class_relations.append((s, o))

        # Get domain and range for properties
        property_domains = {}
        property_ranges = {}

        for prop in obj_properties:
            domains = set()
            for s, p, o in g.triples((prop, RDFS.domain, None)):
                domains.add(o)
            property_domains[prop] = domains

            ranges = set()
            for s, p, o in g.triples((prop, RDFS.range, None)):
                ranges.add(o)
            property_ranges[prop] = ranges

        # Create verbalization data
        ontology_description = create_simple_sentence(
            f"This {domain_type} ontology",
            "contains information about",
            f"{len(individuals)} individuals, {len(classes)} classes, and {len(obj_properties)} properties",
        )

        verbalization_data = {
            "ontology": {
                "name": Path(ontology_file_path).stem,
                "structuralType": domain_type,
                "triples": len(g),
                "description": ontology_description,
            },
            "classes": [],
            "objectProperties": [],
            "dataProperties": [],
            "individuals": [],
        }

        # Add classes without URIs
        print("Generating class descriptions...")
        for cls in sorted(classes, key=lambda x: get_nice_label(g, x) or ""):
            cls_name = get_nice_label(g, cls)
            if cls_name:
                cls_data = {
                    "classLabel": cls_name,
                    "description": describe_class_with_domain_independence(
                        g,
                        cls,
                        classes,
                        subclass_relations,
                        equivalent_class_relations,
                        obj_properties,
                        property_domains,
                        property_ranges,
                    ),
                }
                verbalization_data["classes"].append(cls_data)

        # Add object properties without URIs
        print("Generating property descriptions...")
        for prop in sorted(obj_properties, key=lambda x: get_nice_label(g, x) or ""):
            prop_name = get_nice_label(g, prop)
            if prop_name:
                prop_data = {
                    "propertyLabel": prop_name,
                    "description": describe_property_with_domain_independence(
                        g, prop, obj_properties, property_domains, property_ranges
                    ),
                }
                verbalization_data["objectProperties"].append(prop_data)

        # Add data properties
        for prop in sorted(data_properties, key=lambda x: get_nice_label(g, x) or ""):
            prop_name = get_nice_label(g, prop)
            if prop_name:
                prop_data = {
                    "propertyLabel": prop_name,
                    "description": create_simple_sentence(
                        prop_name, "is", "a data property in this ontology"
                    ),
                }
                verbalization_data["dataProperties"].append(prop_data)

        # Add individuals without URIs - using enhanced description
        print("Generating individual descriptions...")
        for ind in sorted(individuals, key=lambda x: get_nice_label(g, x) or ""):
            ind_name = get_nice_label(g, ind)
            if ind_name:
                ind_data = {
                    "individualLabel": ind_name,
                    "description": describe_individual_with_domain_independence(
                        g, ind, classes, obj_properties, individuals
                    ),
                }
                verbalization_data["individuals"].append(ind_data)

        # Save JSON file
        output_file = output_dir / f"{Path(ontology_file_path).stem}.json"
        with open(output_file, "w", encoding="utf-8") as json_file:
            json.dump(verbalization_data, json_file, indent=2, ensure_ascii=False)

        print(f"Saved verbalization to: {output_file}")
        print(f"Generated descriptions for:")
        print(f"  - {len(verbalization_data['classes'])} classes")
        print(f"  - {len(verbalization_data['objectProperties'])} object properties")
        print(f"  - {len(verbalization_data['dataProperties'])} data properties")
        print(f"  - {len(verbalization_data['individuals'])} individuals")

        return output_file

    except Exception as e:
        print(f"Error during verbalization: {e}")
        import traceback

        print(f"Full traceback: {traceback.format_exc()}")
        return None

    finally:
        # Explicit cleanup
        if "g" in locals():
            g.close()
            del g
        # Clear large local variables
        if "verbalization_data" in locals():
            del verbalization_data
        if "classes" in locals():
            del classes
        if "obj_properties" in locals():
            del obj_properties
        if "data_properties" in locals():
            del data_properties
        if "individuals" in locals():
            del individuals
        gc.collect()


def process_in_batches(ontology_files, output_dir, batch_size=50, memory_threshold=80):
    """Process ontologies in batches with memory management"""
    successful = 0
    failed = 0
    total_batches = (len(ontology_files) + batch_size - 1) // batch_size

    for i in range(0, len(ontology_files), batch_size):
        batch = ontology_files[i : i + batch_size]
        current_batch = i // batch_size + 1

        print(f"\n{'=' * 60}")
        print(f"Processing batch {current_batch}/{total_batches}")
        print(
            f"Files {i + 1} to {min(i + batch_size, len(ontology_files))} of {len(ontology_files)}"
        )
        print(f"Current memory usage: {psutil.virtual_memory().percent:.1f}%")

        # Process current batch
        batch_successful = 0
        batch_failed = 0

        for j, ontology_file in enumerate(batch, 1):
            try:
                print(f"  [{current_batch}.{j}] Processing: {ontology_file.name}")
                result = verbalize_ontology(ontology_file, output_dir)
                if result:
                    successful += 1
                    batch_successful += 1
                    print(f"  âœ… Successfully processed: {ontology_file.name}")
                else:
                    failed += 1
                    batch_failed += 1
                    print(f"  âŒ Failed to process: {ontology_file.name}")
            except Exception as e:
                failed += 1
                batch_failed += 1
                print(f"  âŒ Error processing {ontology_file.name}: {e}")
                import traceback

                print(f"  Full error: {traceback.format_exc()}")

        # Force garbage collection after each batch
        gc.collect()

        # Check memory usage and pause if needed
        memory_percent = psutil.virtual_memory().percent
        if memory_percent > memory_threshold:
            print(
                f"âš ï¸ Memory usage at {memory_percent:.1f}%, pausing for 5 seconds..."
            )
            time.sleep(5)
            gc.collect()

            # If still high memory, try more aggressive cleanup
            new_memory = psutil.virtual_memory().percent
            if new_memory > memory_threshold:
                print(
                    f"âš ï¸ Memory still high at {new_memory:.1f}%, performing aggressive cleanup..."
                )
                # Reinitialize SimpleNLG if available
                if SIMPLENLG_AVAILABLE:
                    try:
                        global lexicon, nlg_factory, realiser
                        del lexicon, nlg_factory, realiser
                        lexicon = Lexicon.getDefaultLexicon()
                        nlg_factory = NLGFactory(lexicon)
                        realiser = Realiser(lexicon)
                        gc.collect()
                        print("ðŸ”„ SimpleNLG reinitialized")
                    except:
                        pass
                time.sleep(5)
                gc.collect()

        print(f"Batch {current_batch}/{total_batches} completed:")
        print(f"  âœ… Successful in batch: {batch_successful}")
        print(f"  âŒ Failed in batch: {batch_failed}")
        print(
            f"  ðŸ“Š Total progress: {successful + failed}/{len(ontology_files)} files ({((successful + failed) / len(ontology_files) * 100):.1f}%)"
        )
        print(f"  ðŸ”§ Memory usage: {psutil.virtual_memory().percent:.1f}%")

        # Small pause between batches to let system breathe
        if current_batch < total_batches:  # Don't pause after the last batch
            time.sleep(2)

    return successful, failed


def main():
    """Main function to process ontology files - works with any domain"""
    import argparse

    parser = argparse.ArgumentParser(
        description="Verbalize ontology files to natural language JSON (domain-independent)"
    )
    parser.add_argument(
        "--input-dir",
        default="src/main/resources/OWL2Bench_2hop",
        help="Input directory containing ontology files",
    )
    parser.add_argument(
        "--output-dir",
        default="output/verbalized_ontologies/OWL2Bench_2hop",
        help="Output directory for JSON files",
    )
    parser.add_argument(
        "--file-pattern",
        default="*.ttl",
        help="File pattern to match (e.g., *.ttl, *.owl, *.rdf)",
    )
    parser.add_argument(
        "--batch-size",
        type=int,
        default=50,
        help="Number of files to process in each batch",
    )
    parser.add_argument(
        "--memory-threshold",
        type=int,
        default=80,
        help="Memory usage threshold (%) to trigger cleanup pause",
    )

    args = parser.parse_args()

    # Create output directory
    output_dir = Path(args.output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)

    # Define input directory
    input_dir = Path(args.input_dir)

    # Check if input directory exists
    if not input_dir.exists():
        print(f"Error: Input directory does not exist: {input_dir}")
        return

    # Find all matching files
    ontology_files = list(input_dir.glob(args.file_pattern))

    if not ontology_files:
        print(f"No files matching '{args.file_pattern}' found in {input_dir}")
        return

    print(f"Found {len(ontology_files)} ontology files to process:")
    for file in ontology_files[:10]:  # Show first 10 files
        print(f"  - {file.name}")
    if len(ontology_files) > 10:
        print(f"  ... and {len(ontology_files) - 10} more files")

    if SIMPLENLG_AVAILABLE:
        print("ðŸŽ¯ Using SimpleNLG for enhanced natural language generation")
    else:
        print("âš ï¸ SimpleNLG not available, using basic text generation")

    print("ðŸ”§ Using domain-independent structural pattern recognition")
    print(f"ðŸ”§ Processing in batches of {args.batch_size} files")
    print(f"ðŸ”§ Memory threshold: {args.memory_threshold}%")
    print(f"ðŸ”§ Current memory usage: {psutil.virtual_memory().percent:.1f}%")

    # Process in batches instead of all at once
    successful, failed = process_in_batches(
        ontology_files,
        output_dir,
        batch_size=args.batch_size,
        memory_threshold=args.memory_threshold,
    )

    print(f"\n{'=' * 60}")
    print(f"Processing completed!")
    print(f"Successful: {successful}")
    print(f"Failed: {failed}")
    print(f"Output directory: {output_dir.absolute()}")
    print(f"Final memory usage: {psutil.virtual_memory().percent:.1f}%")


if __name__ == "__main__":
    main()
