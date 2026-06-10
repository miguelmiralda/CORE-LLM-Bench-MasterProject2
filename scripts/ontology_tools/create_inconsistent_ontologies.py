#!/usr/bin/env python3
"""Create TTL resources with range violation triples.

The input resource files are per-individual Turtle ontologies. This script copies
those TTL files to new output folders. Range violations are written now; class
disjointness and functional-property violation folders are created for later
generators.

Range violations are created from the focused individual represented by the TTL
file. For example, if :person_a has a father :person_b and mother :person_c, the
range output asserts:

    :person_a :hasFather :person_c .

and similarly swaps the father value into :hasMother.
"""

from __future__ import annotations

import argparse
import re
import shutil
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable

from create_contradicted_questions import (
    ViolationQuestion,
    build_contradicted_dependency_phrases,
    count_tbox_abox,
    explanation_belongs_to_root,
    inferred_triple_from_entry,
    load_explanations,
    make_ask_query,
    predicate_name,
    triple_phrase,
    write_csv,
)

URI = r"<([^>]+)>"
QNAME = r"(?:rdf|owl|rdfs):[A-Za-z_][A-Za-z0-9_-]*"
QNAME_URIS = {
    "rdf:type": "http://www.w3.org/1999/02/22-rdf-syntax-ns#type",
    "owl:FunctionalProperty": "http://www.w3.org/2002/07/owl#FunctionalProperty",
    "owl:NamedIndividual": "http://www.w3.org/2002/07/owl#NamedIndividual",
    "owl:differentFrom": "http://www.w3.org/2002/07/owl#differentFrom",
    "owl:inverseOf": "http://www.w3.org/2002/07/owl#inverseOf",
    "owl:disjointWith": "http://www.w3.org/2002/07/owl#disjointWith",
    "rdfs:domain": "http://www.w3.org/2000/01/rdf-schema#domain",
    "rdfs:range": "http://www.w3.org/2000/01/rdf-schema#range",
    "rdfs:subClassOf": "http://www.w3.org/2000/01/rdf-schema#subClassOf",
}
RDF_TYPE = "http://www.w3.org/1999/02/22-rdf-syntax-ns#type"
OWL_FUNCTIONAL_PROPERTY = "http://www.w3.org/2002/07/owl#FunctionalProperty"
OWL_NAMED_INDIVIDUAL = "http://www.w3.org/2002/07/owl#NamedIndividual"
OWL_INVERSE_OF = "http://www.w3.org/2002/07/owl#inverseOf"
OWL_DISJOINT_WITH = "http://www.w3.org/2002/07/owl#disjointWith"
RDFS_DOMAIN = "http://www.w3.org/2000/01/rdf-schema#domain"
RDFS_RANGE = "http://www.w3.org/2000/01/rdf-schema#range"
RDFS_SUBCLASS_OF = "http://www.w3.org/2000/01/rdf-schema#subClassOf"
GENEALOGY_NS = "http://www.example.com/genealogy.owl#"
MAN = GENEALOGY_NS + "Man"
WOMAN = GENEALOGY_NS + "Woman"
PERSON = GENEALOGY_NS + "Person"


@dataclass(frozen=True)
class Triple:
    subject: str
    predicate: str
    object: str


@dataclass(frozen=True)
class GeneratedViolation:
    subject: str
    property_uri: str
    original_object: str
    second_object: str
    range_class: str


@dataclass(frozen=True)
class RangeViolation:
    subject: str
    property_uri: str
    original_object: str
    violating_object: str
    expected_range_class: str
    violating_object_class: str
    replaced_triples: tuple[Triple, ...] = ()
    support_type_assertions: tuple[tuple[str, str], ...] = ()


@dataclass(frozen=True)
class FunctionalAssertion:
    normalized: Triple
    source: Triple


@dataclass(frozen=True)
class PropertyAssertion:
    normalized: Triple
    source: Triple
    is_functional: bool


def local_name(uri: str) -> str:
    if "#" in uri:
        return uri.rsplit("#", 1)[1]
    return uri.rstrip("/").rsplit("/", 1)[-1]


def focus_individual_uri(file_stem: str) -> str:
    individual_name = file_stem.removeprefix("Thing_")
    return GENEALOGY_NS + individual_name


def namespace(uri: str) -> str:
    if "#" in uri:
        return uri.rsplit("#", 1)[0] + "#"
    return uri.rstrip("/").rsplit("/", 1)[0] + "/"


def term_to_uri(term: str) -> str | None:
    term = term.strip().rstrip(";,.")
    if term.startswith("<") and term.endswith(">"):
        return term[1:-1]
    return QNAME_URIS.get(term)


def parse_object_uris(rest_objects: str) -> list[str]:
    objects: list[str] = list(re.findall(URI, rest_objects))

    # Also capture compact OWL/RDF/RDFS terms such as owl:FunctionalProperty.
    scrubbed = re.sub(URI, " ", rest_objects)
    for token in re.findall(r"\b(?:rdf|owl|rdfs):[A-Za-z_][A-Za-z0-9_-]*", scrubbed):
        uri = term_to_uri(token)
        if uri is not None:
            objects.append(uri)

    return objects


def parse_uri_triples(text: str) -> list[Triple]:
    """Parse URI/qname triples from the OWL-API-style Turtle used here.

    This intentionally ignores literals and blank nodes because the violation
    generation only needs object-property/class/schema URI triples.
    """
    triples: list[Triple] = []
    subject: str | None = None
    predicate: str | None = None

    for raw_line in text.splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or line.startswith("@"):
            continue

        subject_match = re.match(rf"^{URI}\s*(.*)$", line)
        if subject_match and subject is not None and predicate is not None:
            possible_tail = subject_match.group(2).strip()
            if possible_tail in {"", ",", ";", "."}:
                triples.append(Triple(subject, predicate, subject_match.group(1)))
                if possible_tail == ".":
                    subject = None
                    predicate = None
                continue

        subject_match = re.match(rf"^{URI}\s+(.*)$", line)
        if subject_match and subject is not None and subject_match.group(2).strip().startswith("<"):
            # Inside a semicolon-delimited subject block, a URI followed by
            # another URI is a predicate/object line, not a new subject.
            rest = line
        elif subject_match:
            subject = subject_match.group(1)
            rest = subject_match.group(2).strip()
            predicate = None
        else:
            rest = line

        if subject is None:
            continue

        predicate_uri: str | None = None
        rest_objects = ""

        qname_predicate = re.match(r"^((?:rdf|owl|rdfs):[A-Za-z_][A-Za-z0-9_-]*)\s+(.*)$", rest)
        uri_predicate = re.match(rf"^{URI}\s+(.*)$", rest)

        if qname_predicate and term_to_uri(qname_predicate.group(1)) is not None:
            predicate_uri = term_to_uri(qname_predicate.group(1))
            rest_objects = qname_predicate.group(2).strip()
        elif uri_predicate:
            predicate_uri = uri_predicate.group(1)
            rest_objects = uri_predicate.group(2).strip()
        elif predicate is not None:
            predicate_uri = predicate
            rest_objects = rest

        if predicate_uri is None:
            continue

        predicate = predicate_uri
        for obj in parse_object_uris(rest_objects):
            triples.append(Triple(subject, predicate, obj))

        if rest.endswith("."):
            subject = None
            predicate = None

    return triples


def collect_functional_properties(triples: Iterable[Triple]) -> set[str]:
    return {
        triple.subject
        for triple in triples
        if triple.predicate == RDF_TYPE and triple.object == OWL_FUNCTIONAL_PROPERTY
    }


def collect_ranges(triples: Iterable[Triple]) -> dict[str, str]:
    return {
        triple.subject: triple.object
        for triple in triples
        if triple.predicate == RDFS_RANGE
    }


def collect_domains(triples: Iterable[Triple]) -> dict[str, str]:
    return {
        triple.subject: triple.object
        for triple in triples
        if triple.predicate == RDFS_DOMAIN
    }


def collect_disjoint_classes(triples: Iterable[Triple]) -> dict[str, set[str]]:
    disjoints: dict[str, set[str]] = {}
    for triple in triples:
        if triple.predicate == OWL_DISJOINT_WITH:
            disjoints.setdefault(triple.subject, set()).add(triple.object)
            disjoints.setdefault(triple.object, set()).add(triple.subject)
    # Man/Woman are incompatible through their hasSex Male/Female restrictions
    # in this ontology, not by a direct owl:disjointWith axiom.
    disjoints.setdefault(MAN, set()).add(WOMAN)
    disjoints.setdefault(WOMAN, set()).add(MAN)
    return disjoints


def collect_inverses(triples: Iterable[Triple]) -> dict[str, str]:
    inverses: dict[str, str] = {}
    for triple in triples:
        if triple.predicate == OWL_INVERSE_OF:
            inverses[triple.subject] = triple.object
            inverses[triple.object] = triple.subject
    return inverses


def existing_named_individuals(triples: Iterable[Triple]) -> set[str]:
    return {
        triple.subject
        for triple in triples
        if triple.predicate == RDF_TYPE and triple.object == OWL_NAMED_INDIVIDUAL
    }


def collect_explicit_types(triples: Iterable[Triple]) -> dict[str, set[str]]:
    types: dict[str, set[str]] = {}
    for triple in triples:
        if triple.predicate == RDF_TYPE:
            types.setdefault(triple.subject, set()).add(triple.object)
    return types


def collect_range_candidate_hints(triples: Iterable[Triple], inverses: dict[str, str]) -> dict[str, set[str]]:
    """Infer simple range-compatible candidates from existing assertions.

    The toy genealogy TTLs often do not directly type people as Man/Woman, but
    they do contain facts like john isFatherOf child or marriage hasMalePartner
    john. Those are good existing candidates for the range of hasFather/Man.
    """
    hints: dict[str, set[str]] = {MAN: set(), WOMAN: set(), PERSON: set()}

    for triple in triples:
        pred = triple.predicate
        pred_name = pred.rsplit("#", 1)[-1]

        if pred_name in {"isFatherOf", "hasMalePartner"}:
            hints[MAN].add(triple.subject if pred_name == "isFatherOf" else triple.object)
        elif pred_name in {"isMotherOf", "hasFemalePartner"}:
            hints[WOMAN].add(triple.subject if pred_name == "isMotherOf" else triple.object)
        elif pred_name in {"hasFather", "hasMalePartner"}:
            hints[MAN].add(triple.object)
        elif pred_name in {"hasMother", "hasFemalePartner"}:
            hints[WOMAN].add(triple.object)

        if pred_name in {"isFatherOf", "isMotherOf"}:
            hints[PERSON].add(triple.object)
        elif pred_name in {"hasFather", "hasMother", "hasParent", "hasChild"}:
            hints[PERSON].add(triple.subject)
            hints[PERSON].add(triple.object)

    hints[PERSON].update(hints[MAN])
    hints[PERSON].update(hints[WOMAN])
    return hints


def find_functional_assertions(
    triples: Iterable[Triple], functional_properties: set[str], inverses: dict[str, str]
) -> list[FunctionalAssertion]:
    assertions: list[FunctionalAssertion] = []
    seen: set[tuple[str, str, str]] = set()

    for triple in triples:
        if triple.predicate in {RDF_TYPE, RDFS_RANGE, OWL_INVERSE_OF}:
            continue

        if triple.predicate in functional_properties:
            normalized = triple
        elif inverses.get(triple.predicate) in functional_properties:
            normalized = Triple(triple.object, inverses[triple.predicate], triple.subject)
        else:
            continue

        key = (normalized.subject, normalized.predicate, normalized.object)
        if key not in seen:
            seen.add(key)
            assertions.append(FunctionalAssertion(normalized=normalized, source=triple))

    return assertions


def find_focus_property_assertions_with_range(
    triples: Iterable[Triple],
    focus_subject: str,
    ranges: dict[str, str],
    inverses: dict[str, str],
    functional_properties: set[str],
) -> list[PropertyAssertion]:
    assertions: list[PropertyAssertion] = []
    seen: set[tuple[str, str, str]] = set()

    for triple in triples:
        if triple.predicate in {RDF_TYPE, RDFS_RANGE, OWL_INVERSE_OF}:
            continue

        if triple.subject == focus_subject and triple.predicate in ranges:
            normalized = triple
        elif triple.object == focus_subject and inverses.get(triple.predicate) in ranges:
            normalized = Triple(triple.object, inverses[triple.predicate], triple.subject)
        else:
            continue

        key = (normalized.subject, normalized.predicate, normalized.object)
        if key in seen:
            continue
        seen.add(key)
        assertions.append(
            PropertyAssertion(
                normalized=normalized,
                source=triple,
                is_functional=normalized.predicate in functional_properties,
            )
        )

    return assertions


def opposite_range_class(range_class: str) -> str | None:
    if range_class == MAN:
        return WOMAN
    if range_class == WOMAN:
        return MAN
    return None


def choose_out_of_range_object(
    expected_range_class: str,
    assertion: Triple,
    individuals: set[str],
    range_hints: dict[str, set[str]],
) -> tuple[str, str] | None:
    violating_class = opposite_range_class(expected_range_class)
    if violating_class is None:
        return None

    excluded = {assertion.subject, assertion.object}
    candidates = {
        individual
        for individual in range_hints.get(violating_class, set())
        if individual in individuals
        and individual not in excluded
        and individual not in range_hints.get(expected_range_class, set())
    }
    if not candidates:
        return None
    return sorted(candidates)[0], violating_class


def remove_exact_triples(triples: Iterable[Triple], triples_to_remove: Iterable[Triple]) -> list[Triple]:
    removal_counts: dict[tuple[str, str, str], int] = {}
    for triple in triples_to_remove:
        key = (triple.subject, triple.predicate, triple.object)
        removal_counts[key] = removal_counts.get(key, 0) + 1

    filtered: list[Triple] = []
    for triple in triples:
        key = (triple.subject, triple.predicate, triple.object)
        if removal_counts.get(key, 0) > 0:
            removal_counts[key] -= 1
        else:
            filtered.append(triple)
    return filtered


def invert_type_map(type_map: dict[str, set[str]]) -> dict[str, set[str]]:
    inverted: dict[str, set[str]] = {}
    for individual, classes in type_map.items():
        for class_uri in classes:
            inverted.setdefault(class_uri, set()).add(individual)
    return inverted


def inferable_individuals_by_class(
    triples: Iterable[Triple],
    inverses: dict[str, str],
    ranges: dict[str, str],
    domains: dict[str, str],
) -> dict[str, set[str]]:
    hints = merge_hint_maps(
        collect_range_candidate_hints(triples, inverses),
        invert_type_map(collect_explicit_types(triples)),
    )

    for triple in triples:
        if triple.predicate in {RDF_TYPE, RDFS_DOMAIN, RDFS_RANGE, OWL_INVERSE_OF}:
            continue

        if triple.predicate in domains:
            hints.setdefault(domains[triple.predicate], set()).add(triple.subject)
        if triple.predicate in ranges:
            hints.setdefault(ranges[triple.predicate], set()).add(triple.object)

        inverse = inverses.get(triple.predicate)
        if inverse is not None:
            if inverse in domains:
                hints.setdefault(domains[inverse], set()).add(triple.object)
            if inverse in ranges:
                hints.setdefault(ranges[inverse], set()).add(triple.subject)

    return hints


def incompatible_classes(expected_range_class: str, disjoints: dict[str, set[str]]) -> set[str]:
    return set(disjoints.get(expected_range_class, set()))


def choose_surviving_out_of_range_object(
    expected_range_class: str,
    assertion: Triple,
    individuals: set[str],
    triples_after_removal: list[Triple],
    inverses: dict[str, str],
    ranges: dict[str, str],
    domains: dict[str, str],
    disjoints: dict[str, set[str]],
) -> tuple[str, str] | None:
    violating_classes = incompatible_classes(expected_range_class, disjoints)
    if not violating_classes:
        return None

    class_hints = inferable_individuals_by_class(triples_after_removal, inverses, ranges, domains)
    excluded = {assertion.subject, assertion.object}
    for violating_class in sorted(violating_classes):
        candidates = {
            individual
            for individual in class_hints.get(violating_class, set())
            if individual in individuals
            and individual not in excluded
            and individual not in class_hints.get(expected_range_class, set())
        }
        if candidates:
            return sorted(candidates)[0], violating_class
    return None


def choose_locally_inferable_out_of_range_object(
    expected_range_class: str,
    assertion: Triple,
    individuals: set[str],
    triples: list[Triple],
    inverses: dict[str, str],
    ranges: dict[str, str],
    domains: dict[str, str],
    disjoints: dict[str, set[str]],
) -> tuple[str, str] | None:
    violating_classes = incompatible_classes(expected_range_class, disjoints)
    if not violating_classes:
        return None

    class_hints = inferable_individuals_by_class(triples, inverses, ranges, domains)
    excluded = {assertion.subject, assertion.object}
    for violating_class in sorted(violating_classes):
        candidates = {
            individual
            for individual in class_hints.get(violating_class, set())
            if individual in individuals
            and individual not in excluded
            and individual not in class_hints.get(expected_range_class, set())
        }
        if candidates:
            return sorted(candidates)[0], violating_class
    return None


def find_direct_connection_triples(
    triples: Iterable[Triple], main_individual: str, other_individual: str
) -> tuple[Triple, ...]:
    """Find existing assertions directly connecting the focus to a chosen value."""
    connected: list[Triple] = []
    seen: set[tuple[str, str, str]] = set()
    for triple in triples:
        should_replace = (
            triple.subject == main_individual
            and triple.object == other_individual
            and triple.predicate != RDF_TYPE
        ) or (
            triple.subject == other_individual
            and triple.object == main_individual
            and triple.predicate != RDF_TYPE
        )
        if not should_replace:
            continue

        key = (triple.subject, triple.predicate, triple.object)
        if key not in seen:
            seen.add(key)
            connected.append(triple)

    return tuple(connected)


def original_range_value(source_triple: Triple, main_individual: str) -> str:
    if source_triple.subject == main_individual:
        return source_triple.object
    return source_triple.subject


def choose_existing_second_object(
    assertion: Triple,
    range_class: str,
    individuals: set[str],
    explicit_types: dict[str, set[str]],
    range_hints: dict[str, set[str]],
) -> str | None:
    excluded = {assertion.subject, assertion.object}

    typed_candidates = {
        individual
        for individual, classes in explicit_types.items()
        if range_class in classes and individual in individuals and individual not in excluded
    }
    hinted_candidates = {
        individual
        for individual in range_hints.get(range_class, set())
        if individual in individuals and individual not in excluded
    }
    # For specific ranges such as Man/Woman, do not fall back to arbitrary
    # individuals: that would mix a functional-property violation with a range
    # violation. Only generic Person-like ranges may use person candidates.
    candidate_sets = [typed_candidates, hinted_candidates]
    if range_class == PERSON:
        person_candidates = {
            individual
            for individual in range_hints.get(PERSON, set())
            if individual in individuals and individual not in excluded
        }
        candidate_sets.append(person_candidates)

    for candidates in candidate_sets:
        if candidates:
            return sorted(candidates)[0]
    return None


def merge_type_maps(*maps: dict[str, set[str]]) -> dict[str, set[str]]:
    merged: dict[str, set[str]] = {}
    for type_map in maps:
        for individual, classes in type_map.items():
            merged.setdefault(individual, set()).update(classes)
    return merged


def merge_hint_maps(*maps: dict[str, set[str]]) -> dict[str, set[str]]:
    merged: dict[str, set[str]] = {MAN: set(), WOMAN: set(), PERSON: set()}
    for hint_map in maps:
        for range_class, individuals in hint_map.items():
            merged.setdefault(range_class, set()).update(individuals)
    return merged


def generate_violations_for_text(
    text: str,
    file_stem: str,
    global_individuals: set[str] | None = None,
    global_explicit_types: dict[str, set[str]] | None = None,
    global_range_hints: dict[str, set[str]] | None = None,
) -> list[GeneratedViolation]:
    triples = parse_uri_triples(text)
    functional_properties = collect_functional_properties(triples)
    ranges = collect_ranges(triples)
    domains = collect_domains(triples)
    disjoints = collect_disjoint_classes(triples)
    inverses = collect_inverses(triples)
    local_individuals = existing_named_individuals(triples)
    local_explicit_types = collect_explicit_types(triples)
    local_range_hints = collect_range_candidate_hints(triples, inverses)

    individuals = set(local_individuals)
    if global_individuals is not None:
        individuals.update(global_individuals)

    explicit_types = merge_type_maps(local_explicit_types, global_explicit_types or {})
    range_hints = merge_hint_maps(local_range_hints, global_range_hints or {})

    violations: list[GeneratedViolation] = []
    assertions = find_functional_assertions(triples, functional_properties, inverses)
    focus_subject = focus_individual_uri(file_stem)

    for assertion in assertions:
        normalized = assertion.normalized
        range_class = ranges.get(normalized.predicate, GENEALOGY_NS + "DomainEntity")

        # Only create violations for subjects/objects that look like individuals,
        # not schema axioms accidentally captured by broad Turtle syntax.
        if (
            normalized.subject != focus_subject
            or normalized.subject not in local_individuals
            or normalized.object not in individuals
        ):
            continue

        second_object = choose_existing_second_object(
            normalized, range_class, individuals, explicit_types, range_hints
        )
        if second_object is None:
            continue

        violations.append(
            GeneratedViolation(
                subject=normalized.subject,
                property_uri=normalized.predicate,
                original_object=normalized.object,
                second_object=second_object,
                range_class=range_class,
            )
        )

    return violations


def generate_range_violations_for_text(
    text: str,
    file_stem: str,
    global_individuals: set[str] | None = None,
    global_range_hints: dict[str, set[str]] | None = None,
    removed_triples: Iterable[Triple] = (),
    added_type_assertions: Iterable[tuple[str, str]] = (),
) -> list[RangeViolation]:
    triples = remove_exact_triples(parse_uri_triples(text), removed_triples)
    for individual_uri, class_uri in added_type_assertions:
        triples.append(Triple(individual_uri, RDF_TYPE, class_uri))
    functional_properties = collect_functional_properties(triples)
    ranges = collect_ranges(triples)
    domains = collect_domains(triples)
    disjoints = collect_disjoint_classes(triples)
    inverses = collect_inverses(triples)
    local_individuals = existing_named_individuals(triples)

    individuals = set(local_individuals)
    if global_individuals is not None:
        individuals.update(global_individuals)

    focus_subject = focus_individual_uri(file_stem)
    assertions = find_focus_property_assertions_with_range(
        triples, focus_subject, ranges, inverses, functional_properties
    )

    candidate_violations: list[tuple[PropertyAssertion, RangeViolation]] = []
    for assertion in assertions:
        normalized = assertion.normalized
        if normalized.subject not in local_individuals or normalized.object not in individuals:
            continue
        expected_range_class = ranges.get(normalized.predicate)
        if expected_range_class is None or not incompatible_classes(expected_range_class, disjoints):
            continue

        out_of_range = choose_locally_inferable_out_of_range_object(
            expected_range_class,
            normalized,
            individuals,
            triples,
            inverses,
            ranges,
            domains,
            disjoints,
        )
        if out_of_range is None:
            continue

        violating_object, violating_object_class = out_of_range
        replaced_triples = list(
            find_direct_connection_triples(triples, focus_subject, violating_object)
        )
        if assertion.is_functional:
            replaced_triples.append(assertion.source)
        candidate_violations.append(
            (
                assertion,
                RangeViolation(
                    subject=normalized.subject,
                    property_uri=normalized.predicate,
                    original_object=normalized.object,
                    violating_object=violating_object,
                    expected_range_class=expected_range_class,
                    violating_object_class=violating_object_class,
                    replaced_triples=tuple(replaced_triples),
                ),
            )
        )

    range_violations: list[RangeViolation] = []
    for assertion, violation in candidate_violations:
        triples_after_candidate_removal = remove_exact_triples(triples, violation.replaced_triples)
        surviving_class_hints = inferable_individuals_by_class(
            triples_after_candidate_removal, inverses, ranges, domains
        )
        support_type_assertions: list[tuple[str, str]] = []

        if violation.violating_object not in surviving_class_hints.get(violation.violating_object_class, set()):
            support_type_assertions.append((violation.violating_object, violation.violating_object_class))
        if assertion.is_functional:
            replaced_value = original_range_value(assertion.source, focus_subject)
            if replaced_value not in surviving_class_hints.get(violation.expected_range_class, set()):
                support_type_assertions.append((replaced_value, violation.expected_range_class))

        range_violations.append(
            RangeViolation(
                subject=violation.subject,
                property_uri=violation.property_uri,
                original_object=violation.original_object,
                violating_object=violation.violating_object,
                expected_range_class=violation.expected_range_class,
                violating_object_class=violation.violating_object_class,
                replaced_triples=violation.replaced_triples,
                support_type_assertions=tuple(support_type_assertions),
            )
        )

    return range_violations


def render_violations(violations: list[GeneratedViolation]) -> str:
    if not violations:
        return ""

    lines = [
        "",
        "#################################################################",
        "#    Generated functional property violations",
        "#################################################################",
        "",
    ]

    for violation in violations:
        lines.extend(
            [
                f"<{violation.subject}> <{violation.property_uri}> <{violation.second_object}> .",
                f"<{violation.second_object}> owl:differentFrom <{violation.original_object}> .",
                "",
            ]
        )

    return "\n".join(lines).rstrip() + "\n"


def render_range_violations(violations: list[RangeViolation]) -> str:
    if not violations:
        return ""

    lines = [
        "",
        "#################################################################",
        "#    Generated range violations",
        "#################################################################",
        "",
    ]

    added_type_assertions: set[tuple[str, str]] = set()
    for violation in violations:
        lines.extend(
            [
                (
                    f"# RANGE_VIOLATION original <{violation.subject}> "
                    f"<{violation.property_uri}> <{violation.original_object}>"
                ),
                (
                    f"# RANGE_VIOLATION added <{violation.subject}> "
                    f"<{violation.property_uri}> <{violation.violating_object}>"
                ),
            ]
        )
        for removed_triple in violation.replaced_triples:
            lines.append(
                f"# RANGE_VIOLATION removed <{removed_triple.subject}> "
                f"<{removed_triple.predicate}> <{removed_triple.object}>"
            )
        for individual_uri, class_uri in violation.support_type_assertions:
            lines.append(f"# RANGE_VIOLATION support_type <{individual_uri}> <{class_uri}>")

        for type_key in violation.support_type_assertions:
            if type_key not in added_type_assertions:
                added_type_assertions.add(type_key)
                individual_uri, class_uri = type_key
                lines.extend(
                    [
                        f"<{individual_uri}> rdf:type <{class_uri}> .",
                        "",
                    ]
                )
        lines.extend(
            [
                f"<{violation.subject}> <{violation.property_uri}> <{violation.violating_object}> .",
                "",
            ]
        )

    return "\n".join(lines).rstrip() + "\n"


def append_before_owl_api_footer(text: str, block: str) -> str:
    if not block:
        return text

    marker = "###  Generated by the OWL API"
    index = text.find(marker)
    if index == -1:
        return text.rstrip() + "\n" + block
    return text[:index].rstrip() + "\n" + block + "\n" + text[index:]


def line_contains_triple_object(line: str, predicate: str, object_uri: str) -> bool:
    return f"<{predicate}>" in line and f"<{object_uri}>" in line


def next_nonempty_line(lines: list[str], start_index: int) -> str | None:
    for line in lines[start_index:]:
        if line.strip():
            return line
    return None


def normalize_turtle_block_endings(lines: list[str]) -> list[str]:
    normalized = list(lines)
    for index, line in enumerate(normalized):
        stripped = line.strip()
        if not stripped.endswith(";"):
            continue

        next_line = next_nonempty_line(normalized, index + 1)
        if (
            next_line is None
            or next_line.strip().startswith("###")
            or next_line.strip().startswith("@")
            or re.match(rf"^{URI}\s+", next_line)
        ):
            normalized[index] = line.rstrip()[:-1] + "."

    return normalized


def normalize_turtle_commas(lines: list[str]) -> list[str]:
    normalized = list(lines)
    for index, line in enumerate(normalized):
        stripped = line.strip()
        if not stripped.endswith(","):
            continue

        next_line = next_nonempty_line(normalized, index + 1)
        if next_line is None:
            normalized[index] = line.rstrip()[:-1] + "."
            continue

        next_stripped = next_line.strip()
        next_is_continuation_object = bool(
            re.match(rf"^(?:{URI}|{QNAME}|\"(?:\\.|[^\"])*\")\s*[,;.]?$", next_stripped)
            or next_stripped.startswith("[")
        )
        if next_is_continuation_object:
            continue

        if next_stripped.startswith("###") or re.match(rf"^{URI}\s+", next_line):
            normalized[index] = line.rstrip()[:-1] + "."
        else:
            normalized[index] = line.rstrip()[:-1] + ";"

    return normalized


def remove_orphan_object_continuations(lines: list[str]) -> list[str]:
    cleaned: list[str] = []
    previous_nonempty = ""
    dropping_orphan_objects = False

    for line in lines:
        stripped = line.strip()
        object_only = bool(re.match(rf"^{URI}\s*[,;.]?$", stripped))

        if object_only and (dropping_orphan_objects or not previous_nonempty.endswith(",")):
            dropping_orphan_objects = not stripped.endswith(".")
            continue

        dropping_orphan_objects = False
        cleaned.append(line)
        if stripped:
            previous_nonempty = stripped

    return cleaned


def remove_triples_from_text(text: str, triples_to_remove: Iterable[Triple]) -> str:
    targets_by_subject: dict[str, set[tuple[str, str]]] = {}
    for triple in triples_to_remove:
        targets_by_subject.setdefault(triple.subject, set()).add((triple.predicate, triple.object))

    if not targets_by_subject:
        return text

    individuals_marker = "#################################################################\n#    Individuals"
    marker_index = text.find(individuals_marker)
    if marker_index != -1:
        prefix = text[:marker_index]
        text_to_update = text[marker_index:]
    else:
        prefix = ""
        text_to_update = text

    kept_lines: list[str] = []
    current_subject: str | None = None
    current_predicate: str | None = None
    removed_any = False

    for line in text_to_update.splitlines():
        stripped = line.strip()
        subject_match = re.match(rf"^{URI}\s+(.*)$", stripped)
        possible_tail = subject_match.group(2).strip() if subject_match else ""
        is_continuation_object = (
            subject_match is not None
            and current_subject is not None
            and current_predicate is not None
            and possible_tail in {"", ",", ";", "."}
        )
        is_predicate_line = (
            subject_match is not None
            and current_subject is not None
            and possible_tail.startswith("<")
        )
        if subject_match and not is_continuation_object and not is_predicate_line:
            current_subject = subject_match.group(1)
            current_predicate = None

        predicate_match = re.match(rf"^{URI}\s+(.*)$", stripped)
        if current_subject is not None and predicate_match and not is_continuation_object:
            possible_predicate = predicate_match.group(1)
            possible_tail = predicate_match.group(2).strip()
            if possible_tail and possible_predicate != current_subject:
                current_predicate = possible_predicate

        should_remove = False
        if current_subject in targets_by_subject:
            should_remove = any(
                line_contains_triple_object(stripped, predicate, object_uri)
                or (current_predicate == predicate and f"<{object_uri}>" in stripped)
                for predicate, object_uri in targets_by_subject[current_subject]
            )

        if should_remove:
            removed_any = True
        else:
            kept_lines.append(line)

        if stripped.endswith("."):
            current_subject = None
            current_predicate = None

    if not removed_any:
        return text

    normalized_lines = remove_orphan_object_continuations(kept_lines)
    normalized_lines = normalize_turtle_commas(normalized_lines)
    normalized_lines = normalize_turtle_block_endings(normalized_lines)
    return prefix + "\n".join(normalized_lines).rstrip() + "\n"


def collect_directory_context(ttl_files: list[Path]) -> tuple[set[str], dict[str, set[str]], dict[str, set[str]]]:
    all_individuals: set[str] = set()
    all_types: dict[str, set[str]] = {}
    all_hints: dict[str, set[str]] = {MAN: set(), WOMAN: set(), PERSON: set()}

    for ttl_file in ttl_files:
        triples = parse_uri_triples(ttl_file.read_text(encoding="utf-8"))
        all_individuals.update(existing_named_individuals(triples))
        all_types = merge_type_maps(all_types, collect_explicit_types(triples))
        all_hints = merge_hint_maps(all_hints, collect_range_candidate_hints(triples, collect_inverses(triples)))

    return all_individuals, all_types, all_hints


def contradicted_questions_for_violations(
    ttl_text: str,
    root_entity: str,
    hop: str,
    explanations_by_key: dict,
    violations: list[RangeViolation],
) -> list[ViolationQuestion]:
    if not violations:
        return []

    original_triples = tuple(
        Triple(violation.subject, violation.property_uri, violation.original_object)
        for violation in violations
    )
    root_entries = [
        entry
        for entry in explanations_by_key.values()
        if explanation_belongs_to_root(entry, root_entity)
    ]
    dependency_phrases = build_contradicted_dependency_phrases(root_entries, original_triples)
    tbox_size, abox_size = count_tbox_abox(ttl_text)

    questions: list[ViolationQuestion] = []
    seen: set[tuple[str, str, str]] = set()
    for entry in root_entries:
        inferred_triple = inferred_triple_from_entry(entry)
        if inferred_triple is None:
            continue
        if triple_phrase(inferred_triple) not in dependency_phrases:
            continue

        key = (inferred_triple.subject, inferred_triple.predicate, inferred_triple.object)
        if key in seen:
            continue
        seen.add(key)
        questions.append(
            ViolationQuestion(
                task_id=(
                    f"{hop}-{root_entity}-contradicted-inference-"
                    f"{local_name(inferred_triple.subject)}-"
                    f"{predicate_name(inferred_triple.predicate)}-"
                    f"{local_name(inferred_triple.object)}-{len(questions) + 1}-BIN"
                ),
                root_entity=root_entity,
                tbox_size=tbox_size,
                abox_size=abox_size,
                task_type="Contradicted Inference",
                query_type="CONTRADICTED_INFERENCE",
                answer_type="BIN",
                sparql_query=make_ask_query(
                    inferred_triple.subject,
                    inferred_triple.predicate,
                    inferred_triple.object,
                ),
                predicate=predicate_name(inferred_triple.predicate),
                answer="UNKNOWN",
            )
        )

    return questions


def build_range_violation_text(base_text: str, violations: list[RangeViolation]) -> str:
    text_without_removed_triples = remove_triples_from_text(
        base_text,
        (
            replaced_triple
            for violation in violations
            for replaced_triple in violation.replaced_triples
        ),
    )
    return append_before_owl_api_footer(
        text_without_removed_triples,
        render_range_violations(violations),
    )


def accepted_range_violations_and_questions(
    text: str,
    file_stem: str,
    hop: str,
    explanations_by_key: dict,
    global_individuals: set[str],
    global_hints: dict[str, set[str]],
) -> tuple[list[RangeViolation], list[ViolationQuestion]]:
    accepted_violations: list[RangeViolation] = []
    accepted_questions: list[ViolationQuestion] = []
    seen_questions: set[tuple[str, str, str]] = set()
    accepted_removed_triples: list[Triple] = []
    accepted_type_assertions: list[tuple[str, str]] = []
    tried_candidates: set[tuple[str, str, str]] = set()

    while True:
        candidates = generate_range_violations_for_text(
            text,
            file_stem,
            global_individuals,
            global_hints,
            accepted_removed_triples,
            accepted_type_assertions,
        )
        candidate = next(
            (
                candidate
                for candidate in candidates
                if (candidate.subject, candidate.property_uri, candidate.violating_object)
                not in tried_candidates
            ),
            None,
        )
        if candidate is None:
            break

        tried_candidates.add((candidate.subject, candidate.property_uri, candidate.violating_object))
        trial_violations = [*accepted_violations, candidate]
        trial_text = build_range_violation_text(text, trial_violations)
        trial_questions = contradicted_questions_for_violations(
            trial_text,
            file_stem,
            hop,
            explanations_by_key,
            trial_violations,
        )
        new_questions = [
            question
            for question in trial_questions
            if (question.sparql_query, question.predicate, question.answer) not in seen_questions
        ]
        if not new_questions:
            continue

        accepted_violations.append(candidate)
        accepted_removed_triples.extend(candidate.replaced_triples)
        accepted_type_assertions.extend(candidate.support_type_assertions)
        for question in new_questions:
            seen_questions.add((question.sparql_query, question.predicate, question.answer))
            accepted_questions.append(question)

    return accepted_violations, accepted_questions


def process_directory(
    input_dir: Path,
    range_output_dir: Path,
    class_output_dir: Path,
    functional_output_dir: Path,
    hop: str,
    explanations_by_key: dict | None = None,
    range_questions_output_file: Path | None = None,
) -> tuple[int, int, int, int, int]:
    functional_output_dir.mkdir(parents=True, exist_ok=True)
    range_output_dir.mkdir(parents=True, exist_ok=True)
    class_output_dir.mkdir(parents=True, exist_ok=True)
    ttl_files = sorted(input_dir.glob("*.ttl"))
    total_range_violations = 0
    range_questions: list[ViolationQuestion] = []
    global_individuals, _, global_hints = collect_directory_context(ttl_files)

    for ttl_file in ttl_files:
        text = ttl_file.read_text(encoding="utf-8")
        if explanations_by_key is None:
            range_violations = generate_range_violations_for_text(
                text, ttl_file.stem, global_individuals, global_hints
            )
            file_questions: list[ViolationQuestion] = []
        else:
            range_violations, file_questions = accepted_range_violations_and_questions(
                text,
                ttl_file.stem,
                hop,
                explanations_by_key,
                global_individuals,
                global_hints,
            )
        range_text = build_range_violation_text(text, range_violations)
        (range_output_dir / ttl_file.name).write_text(range_text, encoding="utf-8")
        total_range_violations += len(range_violations)
        range_questions.extend(file_questions)

    if range_questions_output_file is not None:
        write_csv(range_questions, range_questions_output_file)

    return len(ttl_files), total_range_violations, 0, 0, len(range_questions)


def optional_existing_file(path: Path | None, label: str) -> Path | None:
    if path is None:
        return None
    if not path.is_file():
        raise FileNotFoundError(f"{label} does not exist: {path}")
    return path


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Create inconsistent 1-hop and 2-hop TTL resources."
    )
    parser.add_argument(
        "--one-hop-input-dir",
        type=Path,
        required=True,
        help="Directory containing the original 1-hop TTL files.",
    )
    parser.add_argument(
        "--two-hop-input-dir",
        type=Path,
        required=True,
        help="Directory containing the original 2-hop TTL files.",
    )
    parser.add_argument(
        "--output-root",
        type=Path,
        required=True,
        help="Directory where the six inconsistent ontology directories will be created.",
    )
    parser.add_argument(
        "--one-hop-explanations",
        type=Path,
        help="Optional Explanations.json for filtering 1-hop range violations and generating a CSV concurrently.",
    )
    parser.add_argument(
        "--two-hop-explanations",
        type=Path,
        help="Optional Explanations.json for filtering 2-hop range violations and generating a CSV concurrently.",
    )
    parser.add_argument(
        "--one-hop-range-questions-output",
        type=Path,
        help="Optional CSV path for 1-hop contradicted range-violation questions.",
    )
    parser.add_argument(
        "--two-hop-range-questions-output",
        type=Path,
        help="Optional CSV path for 2-hop contradicted range-violation questions.",
    )
    parser.add_argument(
        "--clean",
        action="store_true",
        help="Remove existing output directories before regenerating.",
    )
    args = parser.parse_args()
    one_hop_explanations = optional_existing_file(args.one_hop_explanations, "1-hop explanations file")
    two_hop_explanations = optional_existing_file(args.two_hop_explanations, "2-hop explanations file")
    explanations_by_hop = {
        "1hop": load_explanations(one_hop_explanations) if one_hop_explanations is not None else None,
        "2hop": load_explanations(two_hop_explanations) if two_hop_explanations is not None else None,
    }

    jobs = [
        (
            "1hop",
            "1-hop",
            args.one_hop_input_dir,
            args.output_root / "1_hop_range_violation",
            args.output_root / "1_hop_class_disjointness",
            args.output_root / "1_hop_func_prop_violation",
            args.one_hop_range_questions_output,
        ),
        (
            "2hop",
            "2-hop",
            args.two_hop_input_dir,
            args.output_root / "2_hop_range_violation",
            args.output_root / "2_hop_class_disjointness",
            args.output_root / "2_hop_func_prop_violation",
            args.two_hop_range_questions_output,
        ),
    ]

    for _, _, input_dir, _, _, _, _ in jobs:
        if not input_dir.is_dir():
            raise FileNotFoundError(f"Input directory does not exist: {input_dir}")

    output_dirs = [output_dir for _, _, _, *dirs, _ in jobs for output_dir in dirs]
    for output_dir in output_dirs:
        if args.clean and output_dir.exists():
            shutil.rmtree(output_dir)

    total_files = 0
    total_range = 0
    total_class = 0
    total_functional = 0
    total_range_questions = 0
    for hop, label, input_dir, range_dir, class_dir, functional_dir, range_questions_output in jobs:
        file_count, range_count, class_count, functional_count, question_count = process_directory(
            input_dir,
            range_dir,
            class_dir,
            functional_dir,
            hop,
            explanations_by_hop[hop],
            range_questions_output,
        )
        total_files += file_count
        total_range += range_count
        total_class += class_count
        total_functional += functional_count
        total_range_questions += question_count
        print(f"{label}: {input_dir} -> {range_dir}: {file_count} files, {range_count} range violations")
        if range_questions_output is not None:
            print(f"{label}: wrote {question_count} range-violation questions to {range_questions_output}")
        print(f"{label}: {input_dir} -> {class_dir}: {file_count} files, {class_count} class-disjointness violations")
        print(f"{label}: {input_dir} -> {functional_dir}: {file_count} files, {functional_count} functional-property violations")

    print(
        f"Done: {total_files} files, "
        f"{total_range} range violations, "
        f"{total_class} class-disjointness violations, "
        f"{total_functional} functional-property violations, "
        f"{total_range_questions} range-violation questions"
    )


if __name__ == "__main__":
    main()
