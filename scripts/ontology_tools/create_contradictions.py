#!/usr/bin/env python3
"""Create range-violation contradicted ontologies and matching SPARQL CSVs.

This script follows the range-violation pseudocode in the project notes:

* iterate through each focused ontology,
* choose object-property assertions involving the main individual,
* replace a value with an individual from a disjoint range class,
* remove direct connections that would create other contradiction types,
* add support rdf:type triples when a removal would hide an individual's class,
* keep a temporary change only when it yields contradicted inference questions.

The question generation uses Explanations.json as the inference dependency
source, matching the rest of this benchmark pipeline.
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
    count_tbox_abox,
    explanation_belongs_to_root,
    inferred_triple_from_entry,
    load_explanations,
    make_ask_query,
    predicate_name,
    triple_phrase,
    write_csv,
)
from create_inconsistent_ontologies import (
    GENEALOGY_NS,
    OWL_NAMED_INDIVIDUAL,
    RDF_TYPE,
    Triple,
    RangeViolation,
    build_range_violation_text,
    collect_directory_context,
    collect_disjoint_classes,
    collect_domains,
    collect_functional_properties,
    collect_inverses,
    collect_ranges,
    existing_named_individuals,
    find_direct_connection_triples,
    find_focus_property_assertions_with_range,
    find_functional_assertions,
    incompatible_classes,
    inferable_individuals_by_class,
    original_range_value,
    parse_uri_triples,
    local_name,
    namespace,
    remove_triples_from_text,
    remove_exact_triples,
    append_before_owl_api_footer,
)

OWL_OBJECT_PROPERTY = "http://www.w3.org/2002/07/owl#ObjectProperty"


@dataclass(frozen=True)
class FunctionalViolation:
    subject: str
    property_uri: str
    original_object: str
    second_object: str
    range_class: str
    replaced_triples: tuple[Triple, ...] = ()
    support_type_assertions: tuple[tuple[str, str], ...] = ()


@dataclass(frozen=True)
class ClassDisjointnessViolation:
    individual: str
    class_uri: str


def triple_key(triple: Triple) -> tuple[str, str, str]:
    return triple.subject, triple.predicate, triple.object


def collect_object_properties(text: str) -> set[str]:
    return set(
        re.findall(
            r"<([^>]+)>\s+rdf:type\s+owl:ObjectProperty\b",
            text,
        )
    )


def unique_append_triple(triples: list[Triple], triple: Triple) -> None:
    if triple_key(triple) not in {triple_key(existing) for existing in triples}:
        triples.append(triple)


def unique_append_type(type_assertions: list[tuple[str, str]], individual: str, class_uri: str) -> None:
    if (individual, class_uri) not in type_assertions:
        type_assertions.append((individual, class_uri))


def triples_with_type_support(
    triples: Iterable[Triple], type_assertions: Iterable[tuple[str, str]]
) -> list[Triple]:
    supported = list(triples)
    for individual_uri, class_uri in type_assertions:
        supported.append(Triple(individual_uri, RDF_TYPE, class_uri))
    return supported


def class_hints_for_state(
    triples: Iterable[Triple], type_assertions: Iterable[tuple[str, str]]
) -> dict[str, set[str]]:
    supported_triples = triples_with_type_support(triples, type_assertions)
    return inferable_individuals_by_class(
        supported_triples,
        collect_inverses(supported_triples),
        collect_ranges(supported_triples),
        collect_domains(supported_triples),
    )


def inferred_classes_for_individual(
    class_hints: dict[str, set[str]],
    individual_uri: str,
    disjoints: dict[str, set[str]],
    ontology_ns: str,
) -> list[str]:
    classes = [
        class_uri
        for class_uri, individuals in class_hints.items()
        if individual_uri in individuals
        and class_uri != OWL_NAMED_INDIVIDUAL
        and class_uri.startswith(ontology_ns)
    ]
    disjoint_classes = [class_uri for class_uri in classes if incompatible_classes(class_uri, disjoints)]
    return sorted(disjoint_classes or classes)


def add_missing_class_support(
    current_triples: list[Triple],
    temporary_removed_triples: list[Triple],
    current_type_assertions: list[tuple[str, str]],
    temporary_type_assertions: list[tuple[str, str]],
    individuals_to_check: Iterable[str],
    disjoints: dict[str, set[str]],
    required_classes: dict[str, set[str]] | None = None,
) -> None:
    before_hints = class_hints_for_state(current_triples, current_type_assertions)
    after_triples = remove_exact_triples(current_triples, temporary_removed_triples)
    after_hints = class_hints_for_state(after_triples, [*current_type_assertions, *temporary_type_assertions])

    for individual_uri in individuals_to_check:
        candidate_classes = set(
            inferred_classes_for_individual(
                before_hints,
                individual_uri,
                disjoints,
                ontology_namespace_from_triples(current_triples),
            )
        )
        if required_classes is not None:
            candidate_classes.update(required_classes.get(individual_uri, set()))

        for class_uri in sorted(candidate_classes):
            if individual_uri in after_hints.get(class_uri, set()):
                continue
            unique_append_type(current_type_assertions, individual_uri, class_uri)
            unique_append_type(temporary_type_assertions, individual_uri, class_uri)
            after_hints.setdefault(class_uri, set()).add(individual_uri)


def choose_individual_from_wrong_class(
    current_triples: list[Triple],
    current_type_assertions: list[tuple[str, str]],
    wrong_class: str,
    main_individual: str,
    individuals: set[str],
) -> str | None:
    class_hints = class_hints_for_state(current_triples, current_type_assertions)
    candidates = sorted(
        individual_uri
        for individual_uri in class_hints.get(wrong_class, set())
        if individual_uri in individuals and individual_uri != main_individual
    )
    return candidates[0] if candidates else None


def choose_individual_from_class(
    current_triples: list[Triple],
    current_type_assertions: list[tuple[str, str]],
    class_uri: str,
    excluded_individuals: set[str],
    individuals: set[str],
) -> str | None:
    class_hints = class_hints_for_state(current_triples, current_type_assertions)
    candidates = sorted(
        individual_uri
        for individual_uri in class_hints.get(class_uri, set())
        if individual_uri in individuals and individual_uri not in excluded_individuals
    )
    return candidates[0] if candidates else None


def question_key(question: ViolationQuestion) -> tuple[str, str, str, str]:
    return question.task_id, question.sparql_query, question.predicate, question.answer


def ontology_namespace_from_triples(triples: Iterable[Triple]) -> str:
    for individual_uri in sorted(existing_named_individuals(triples)):
        return namespace(individual_uri)
    return GENEALOGY_NS


def focus_local_name_candidates(file_stem: str) -> list[str]:
    candidates = [file_stem.removeprefix("Thing_")]
    if "_" in file_stem:
        candidates.append(file_stem.split("_", 1)[1])
    return list(dict.fromkeys(candidates))


def focus_individual_uri(file_stem: str, triples: Iterable[Triple]) -> str:
    triples = list(triples)
    individuals = existing_named_individuals(triples)
    candidates = focus_local_name_candidates(file_stem)
    for candidate in candidates:
        for individual_uri in sorted(individuals):
            if local_name(individual_uri) == candidate:
                return individual_uri
    return ontology_namespace_from_triples(triples) + candidates[0]


def uri_for_name(name: str, ontology_ns: str) -> str:
    if name == "rdf:type":
        return RDF_TYPE
    if name.startswith("http://") or name.startswith("https://"):
        return name
    return ontology_ns + name


def inferred_triple_from_entry_with_namespace(entry: dict, ontology_ns: str) -> Triple | None:
    inferred = entry.get("inferred", {})
    subject_name = inferred.get("subject")
    predicate_name_value = inferred.get("predicate")
    object_name = inferred.get("object")
    if not subject_name or not predicate_name_value or not object_name:
        return None
    return Triple(
        uri_for_name(subject_name, ontology_ns),
        uri_for_name(predicate_name_value, ontology_ns),
        uri_for_name(object_name, ontology_ns),
    )


def root_entries(explanations_by_key: dict, file_stem: str) -> list[dict]:
    return [
        entry
        for entry in explanations_by_key.values()
        if explanation_belongs_to_root(entry, file_stem)
    ]


def entries_depending_on_original_triples(
    explanations_by_key: dict,
    file_stem: str,
    original_triples: Iterable[Triple],
    ontology_ns: str,
) -> list[dict]:
    phrases = {triple_phrase(triple) for triple in original_triples}
    entries: list[dict] = []
    changed = True
    while changed:
        changed = False
        for entry in root_entries(explanations_by_key, file_stem):
            explanations = entry.get("explanations", [])
            if not any(
                any(phrase in step for phrase in phrases)
                for explanation in explanations
                for step in explanation
            ):
                continue
            if entry not in entries:
                entries.append(entry)
            inferred_triple = inferred_triple_from_entry_with_namespace(entry, ontology_ns)
            if inferred_triple is None:
                continue
            phrase = triple_phrase(inferred_triple)
            if phrase not in phrases:
                phrases.add(phrase)
                changed = True
    return entries


def questions_for_entries(
    ttl_text: str,
    file_stem: str,
    hop: str,
    entries: Iterable[dict],
    violation_label: str,
    ontology_ns: str,
) -> list[ViolationQuestion]:
    tbox_size, abox_size = count_tbox_abox(ttl_text)
    questions: list[ViolationQuestion] = []
    seen: set[tuple[str, str, str]] = set()
    for entry in entries:
        inferred_triple = inferred_triple_from_entry_with_namespace(entry, ontology_ns)
        if inferred_triple is None:
            continue

        sparql_queries = entry.get("sparqlQueries") or [
            make_ask_query(
                inferred_triple.subject,
                inferred_triple.predicate,
                inferred_triple.object,
            )
        ]
        task_ids = entry.get("taskIds") or []

        for index, sparql_query in enumerate(sparql_queries):
            original_task_id = task_ids[index] if index < len(task_ids) else ""
            answer_type = "MC" if str(original_task_id).endswith("-MC") else "BIN"
            fallback_suffix = f"{predicate_name(inferred_triple.predicate)}-{index + 1}-{answer_type}"
            task_id = (
                f"{original_task_id}-{violation_label}"
                if original_task_id
                else f"{hop}-{file_stem}-{violation_label}-{fallback_suffix}"
            )
            key = (task_id, sparql_query, predicate_name(inferred_triple.predicate))
            if key in seen:
                continue
            seen.add(key)

            questions.append(
                ViolationQuestion(
                    task_id=task_id,
                    root_entity=file_stem,
                    tbox_size=tbox_size,
                    abox_size=abox_size,
                    task_type="Contradicted Inference",
                    query_type="CONTRADICTED_INFERENCE",
                    answer_type=answer_type,
                    sparql_query=sparql_query,
                    predicate=predicate_name(inferred_triple.predicate),
                    answer="UNKNOWN",
                )
            )
    return questions


def contradicted_questions_for_range_violations(
    ttl_text: str,
    file_stem: str,
    hop: str,
    explanations_by_key: dict,
    violations: list[RangeViolation],
    ontology_ns: str,
) -> list[ViolationQuestion]:
    if not violations:
        return []
    original_triples = [
        Triple(violation.subject, violation.property_uri, violation.original_object)
        for violation in violations
    ]
    entries = entries_depending_on_original_triples(
        explanations_by_key,
        file_stem,
        original_triples,
        ontology_ns,
    )
    return questions_for_entries(
        ttl_text,
        file_stem,
        hop,
        entries,
        "range-violation",
        ontology_ns,
    )


def render_functional_violations(violations: list[FunctionalViolation]) -> str:
    if not violations:
        return ""

    lines = [
        "",
        "#################################################################",
        "#    Generated functional property violations",
        "#################################################################",
        "",
    ]
    added_type_assertions: set[tuple[str, str]] = set()
    for violation in violations:
        lines.extend(
            [
                (
                    f"# FUNCTIONAL_PROPERTY_VIOLATION original <{violation.subject}> "
                    f"<{violation.property_uri}> <{violation.original_object}>"
                ),
                (
                    f"# FUNCTIONAL_PROPERTY_VIOLATION added <{violation.subject}> "
                    f"<{violation.property_uri}> <{violation.second_object}>"
                ),
            ]
        )
        for removed_triple in violation.replaced_triples:
            lines.append(
                f"# FUNCTIONAL_PROPERTY_VIOLATION removed <{removed_triple.subject}> "
                f"<{removed_triple.predicate}> <{removed_triple.object}>"
            )
        for type_key in violation.support_type_assertions:
            lines.append(f"# FUNCTIONAL_PROPERTY_VIOLATION support_type <{type_key[0]}> <{type_key[1]}>")
            if type_key not in added_type_assertions:
                added_type_assertions.add(type_key)
                lines.append(f"<{type_key[0]}> rdf:type <{type_key[1]}> .")
        lines.extend(
            [
                f"<{violation.subject}> <{violation.property_uri}> <{violation.second_object}> .",
                f"<{violation.second_object}> owl:differentFrom <{violation.original_object}> .",
                "",
            ]
        )
    return "\n".join(lines).rstrip() + "\n"


def build_functional_violation_text(base_text: str, violations: list[FunctionalViolation]) -> str:
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
        render_functional_violations(violations),
    )


def create_functional_contradictions_for_text(
    text: str,
    file_stem: str,
    hop: str,
    explanations_by_key: dict,
    global_individuals: set[str],
) -> tuple[list[FunctionalViolation], list[ViolationQuestion]]:
    original_triples = parse_uri_triples(text)
    ontology_ns = ontology_namespace_from_triples(original_triples)
    ranges = collect_ranges(original_triples)
    inverses = collect_inverses(original_triples)
    disjoints = collect_disjoint_classes(original_triples)
    functional_properties = collect_functional_properties(original_triples)
    local_individuals = existing_named_individuals(original_triples)
    individuals = set(local_individuals) | set(global_individuals)
    main_individual = focus_individual_uri(file_stem, original_triples)
    assertions = [
        assertion
        for assertion in find_functional_assertions(original_triples, functional_properties, inverses)
        if assertion.normalized.subject == main_individual or assertion.normalized.object == main_individual
    ]

    added_type_assertions: list[tuple[str, str]] = []
    removed_triples: list[Triple] = []
    accepted_violations: list[FunctionalViolation] = []
    accepted_questions: list[ViolationQuestion] = []
    seen_questions: set[tuple[str, str, str, str]] = set()
    seen_added_triples: set[tuple[str, str, str]] = set()

    for assertion in assertions:
        normalized = assertion.normalized
        correct_class = ranges.get(normalized.predicate)
        if correct_class is None:
            continue

        current_triples = remove_exact_triples(original_triples, removed_triples)
        second_object = choose_individual_from_class(
            current_triples,
            added_type_assertions,
            correct_class,
            {normalized.subject, normalized.object},
            individuals,
        )
        if second_object is None:
            continue

        new_triple = Triple(main_individual, normalized.predicate, second_object)
        if triple_key(new_triple) in seen_added_triples:
            continue

        temporary_removed: list[Triple] = []
        temporary_type_assertions: list[tuple[str, str]] = []
        for connected_triple in find_direct_connection_triples(
            current_triples, main_individual, second_object
        ):
            unique_append_triple(removed_triples, connected_triple)
            unique_append_triple(temporary_removed, connected_triple)
            add_missing_class_support(
                current_triples,
                temporary_removed,
                added_type_assertions,
                temporary_type_assertions,
                (connected_triple.subject, connected_triple.object),
                disjoints,
                {second_object: {correct_class}},
            )

        candidate_violation = FunctionalViolation(
            subject=new_triple.subject,
            property_uri=new_triple.predicate,
            original_object=normalized.object,
            second_object=new_triple.object,
            range_class=correct_class,
            replaced_triples=tuple(temporary_removed),
            support_type_assertions=tuple(temporary_type_assertions),
        )
        trial_violations = [*accepted_violations, candidate_violation]
        trial_text = build_functional_violation_text(text, trial_violations)
        trial_entries = entries_depending_on_original_triples(
            explanations_by_key,
            file_stem,
            [Triple(normalized.subject, normalized.predicate, normalized.object)],
            ontology_ns,
        )
        trial_questions = questions_for_entries(
            trial_text,
            file_stem,
            hop,
            trial_entries,
            "functional-property-violation",
            ontology_ns,
        )
        new_questions = [
            question for question in trial_questions if question_key(question) not in seen_questions
        ]
        if not new_questions:
            removed_triples = [
                triple
                for triple in removed_triples
                if triple_key(triple) not in {triple_key(removed) for removed in temporary_removed}
            ]
            added_type_assertions = [
                type_assertion
                for type_assertion in added_type_assertions
                if type_assertion not in temporary_type_assertions
            ]
            continue

        accepted_violations.append(candidate_violation)
        seen_added_triples.add(triple_key(new_triple))
        for question in new_questions:
            seen_questions.add(question_key(question))
            accepted_questions.append(question)

    return accepted_violations, accepted_questions


def explanation_step_triple(step: str, object_properties: set[str], ontology_ns: str) -> Triple | None:
    tokens = step.split()
    if len(tokens) < 3:
        return None
    predicate_uri = uri_for_name(tokens[1], ontology_ns)
    if predicate_uri not in object_properties:
        return None
    return Triple(uri_for_name(tokens[0], ontology_ns), predicate_uri, uri_for_name(tokens[2], ontology_ns))


def individual_classes(
    class_hints: dict[str, set[str]],
    individual_uri: str,
    disjoints: dict[str, set[str]],
    ontology_ns: str,
) -> list[str]:
    return [
        class_uri
        for class_uri in inferred_classes_for_individual(
            class_hints,
            individual_uri,
            disjoints,
            ontology_ns,
        )
        if incompatible_classes(class_uri, disjoints)
    ]


def disjoint_type_for_individual(
    class_hints: dict[str, set[str]],
    individual_uri: str,
    disjoints: dict[str, set[str]],
    ontology_ns: str,
) -> tuple[str, str] | None:
    for class_uri in individual_classes(class_hints, individual_uri, disjoints, ontology_ns):
        for disjoint_class in sorted(incompatible_classes(class_uri, disjoints)):
            return individual_uri, disjoint_class
    return None


def render_class_disjointness_violations(violations: list[ClassDisjointnessViolation]) -> str:
    if not violations:
        return ""

    lines = [
        "",
        "#################################################################",
        "#    Generated class disjointness violations",
        "#################################################################",
        "",
    ]
    seen: set[tuple[str, str]] = set()
    for violation in violations:
        key = (violation.individual, violation.class_uri)
        if key in seen:
            continue
        seen.add(key)
        lines.extend(
            [
                f"# CLASS_DISJOINTNESS_VIOLATION added <{violation.individual}> rdf:type <{violation.class_uri}>",
                f"<{violation.individual}> rdf:type <{violation.class_uri}> .",
                "",
            ]
        )
    return "\n".join(lines).rstrip() + "\n"


def build_class_disjointness_text(
    base_text: str, violations: list[ClassDisjointnessViolation]
) -> str:
    return append_before_owl_api_footer(
        base_text,
        render_class_disjointness_violations(violations),
    )


def create_class_disjointness_contradictions_for_text(
    text: str,
    file_stem: str,
    hop: str,
    explanations_by_key: dict,
) -> tuple[list[ClassDisjointnessViolation], list[ViolationQuestion]]:
    original_triples = parse_uri_triples(text)
    ontology_ns = ontology_namespace_from_triples(original_triples)
    disjoints = collect_disjoint_classes(original_triples)
    object_properties = collect_object_properties(text)
    class_hints = class_hints_for_state(original_triples, [])
    accepted_violations: list[ClassDisjointnessViolation] = []
    accepted_questions: list[ViolationQuestion] = []
    seen_questions: set[tuple[str, str, str, str]] = set()
    seen_type_assertions: set[tuple[str, str]] = set()

    for entry in root_entries(explanations_by_key, file_stem):
        explanations = entry.get("explanations", [])
        if not explanations:
            continue

        temporary_violations: list[ClassDisjointnessViolation] = []
        explanations_broken = 0
        for explanation in explanations:
            for step in explanation:
                assertion = explanation_step_triple(step, object_properties, ontology_ns)
                if assertion is None:
                    continue
                type_assertion = (
                    disjoint_type_for_individual(class_hints, assertion.subject, disjoints, ontology_ns)
                    or disjoint_type_for_individual(class_hints, assertion.object, disjoints, ontology_ns)
                )
                if type_assertion is None:
                    continue
                if type_assertion not in seen_type_assertions:
                    temporary_violations.append(ClassDisjointnessViolation(*type_assertion))
                explanations_broken += 1
                break

        if explanations_broken != len(explanations) or not temporary_violations:
            continue

        trial_violations = [*accepted_violations, *temporary_violations]
        trial_text = build_class_disjointness_text(text, trial_violations)
        trial_questions = questions_for_entries(
            trial_text,
            file_stem,
            hop,
            [entry],
            "class-disjointness",
            ontology_ns,
        )
        new_questions = [
            question for question in trial_questions if question_key(question) not in seen_questions
        ]
        if not new_questions:
            continue

        for violation in temporary_violations:
            key = (violation.individual, violation.class_uri)
            if key not in seen_type_assertions:
                seen_type_assertions.add(key)
                accepted_violations.append(violation)
        for question in new_questions:
            seen_questions.add(question_key(question))
            accepted_questions.append(question)

    return accepted_violations, accepted_questions


def create_range_contradictions_for_text(
    text: str,
    file_stem: str,
    hop: str,
    explanations_by_key: dict,
    global_individuals: set[str],
) -> tuple[list[RangeViolation], list]:
    original_triples = parse_uri_triples(text)
    ontology_ns = ontology_namespace_from_triples(original_triples)
    ranges = collect_ranges(original_triples)
    inverses = collect_inverses(original_triples)
    disjoints = collect_disjoint_classes(original_triples)
    object_properties = collect_object_properties(text)
    functional_properties = collect_functional_properties(original_triples)
    local_individuals = existing_named_individuals(original_triples)
    individuals = set(local_individuals) | set(global_individuals)
    main_individual = focus_individual_uri(file_stem, original_triples)

    assertions = find_focus_property_assertions_with_range(
        original_triples,
        main_individual,
        ranges,
        inverses,
        functional_properties,
    )

    added_type_assertions: list[tuple[str, str]] = []
    removed_triples: list[Triple] = []
    accepted_violations: list[RangeViolation] = []
    accepted_questions = []
    seen_questions: set[tuple[str, str, str, str]] = set()
    seen_added_triples: set[tuple[str, str, str]] = set()

    for assertion in assertions:
        normalized = assertion.normalized
        if (
            assertion.source.predicate not in object_properties
            and normalized.predicate not in object_properties
        ):
            continue
        expected_class = ranges.get(normalized.predicate)
        if expected_class is None:
            continue

        wrong_classes = sorted(incompatible_classes(expected_class, disjoints))
        if not wrong_classes:
            continue

        current_triples = remove_exact_triples(original_triples, removed_triples)
        wrong_individual = None
        wrong_class = None
        for candidate_wrong_class in wrong_classes:
            wrong_individual = choose_individual_from_wrong_class(
                current_triples,
                added_type_assertions,
                candidate_wrong_class,
                main_individual,
                individuals,
            )
            if wrong_individual is not None:
                wrong_class = candidate_wrong_class
                break
        if wrong_individual is None or wrong_class is None:
            continue

        new_triple = Triple(main_individual, normalized.predicate, wrong_individual)
        if triple_key(new_triple) in seen_added_triples:
            continue

        temporary_removed: list[Triple] = []
        temporary_type_assertions: list[tuple[str, str]] = []

        for connected_triple in find_direct_connection_triples(
            current_triples, main_individual, wrong_individual
        ):
            unique_append_triple(removed_triples, connected_triple)
            unique_append_triple(temporary_removed, connected_triple)
            add_missing_class_support(
                current_triples,
                temporary_removed,
                added_type_assertions,
                temporary_type_assertions,
                (connected_triple.subject, connected_triple.object),
                disjoints,
                {wrong_individual: {wrong_class}},
            )

        if assertion.is_functional:
            unique_append_triple(removed_triples, assertion.source)
            unique_append_triple(temporary_removed, assertion.source)
            replaced_value = original_range_value(assertion.source, main_individual)
            add_missing_class_support(
                current_triples,
                temporary_removed,
                added_type_assertions,
                temporary_type_assertions,
                (assertion.source.subject, assertion.source.object),
                disjoints,
                {
                    replaced_value: {expected_class},
                    wrong_individual: {wrong_class},
                },
            )

        unique_append_type(added_type_assertions, wrong_individual, wrong_class)
        unique_append_type(temporary_type_assertions, wrong_individual, wrong_class)

        candidate_violation = RangeViolation(
            subject=new_triple.subject,
            property_uri=new_triple.predicate,
            original_object=normalized.object,
            violating_object=new_triple.object,
            expected_range_class=expected_class,
            violating_object_class=wrong_class,
            replaced_triples=tuple(temporary_removed),
            support_type_assertions=tuple(temporary_type_assertions),
        )
        trial_violations = [*accepted_violations, candidate_violation]
        trial_text = build_range_violation_text(text, trial_violations)
        trial_questions = contradicted_questions_for_range_violations(
            trial_text,
            file_stem,
            hop,
            explanations_by_key,
            trial_violations,
            ontology_ns,
        )
        new_questions = [
            question
            for question in trial_questions
            if question_key(question) not in seen_questions
        ]

        if not new_questions:
            removed_triples = [
                triple
                for triple in removed_triples
                if triple_key(triple) not in {triple_key(removed) for removed in temporary_removed}
            ]
            added_type_assertions = [
                type_assertion
                for type_assertion in added_type_assertions
                if type_assertion not in temporary_type_assertions
            ]
            continue

        accepted_violations.append(candidate_violation)
        seen_added_triples.add(triple_key(new_triple))
        for question in new_questions:
            seen_questions.add(question_key(question))
            accepted_questions.append(question)

    return accepted_violations, accepted_questions


def process_directory(
    input_dir: Path,
    range_output_dir: Path,
    class_disjointness_output_dir: Path,
    functional_property_output_dir: Path,
    range_questions_output_file: Path,
    class_disjointness_questions_output_file: Path,
    functional_property_questions_output_file: Path,
    hop: str,
    explanations_by_key: dict,
) -> tuple[int, int, int, int, int, int, int]:
    range_output_dir.mkdir(parents=True, exist_ok=True)
    class_disjointness_output_dir.mkdir(parents=True, exist_ok=True)
    functional_property_output_dir.mkdir(parents=True, exist_ok=True)
    ttl_files = sorted(input_dir.glob("*.ttl"))
    global_individuals, _, _ = collect_directory_context(ttl_files)

    range_questions: list[ViolationQuestion] = []
    class_questions: list[ViolationQuestion] = []
    functional_questions: list[ViolationQuestion] = []
    total_range_violations = 0
    total_class_violations = 0
    total_functional_violations = 0
    for ttl_file in ttl_files:
        text = ttl_file.read_text(encoding="utf-8")
        range_violations, questions = create_range_contradictions_for_text(
            text,
            ttl_file.stem,
            hop,
            explanations_by_key,
            global_individuals,
        )
        range_text = build_range_violation_text(text, range_violations)
        (range_output_dir / ttl_file.name).write_text(range_text, encoding="utf-8")
        total_range_violations += len(range_violations)
        range_questions.extend(questions)

        class_violations, questions = create_class_disjointness_contradictions_for_text(
            text,
            ttl_file.stem,
            hop,
            explanations_by_key,
        )
        class_text = build_class_disjointness_text(text, class_violations)
        (class_disjointness_output_dir / ttl_file.name).write_text(class_text, encoding="utf-8")
        total_class_violations += len(class_violations)
        class_questions.extend(questions)

        functional_violations, questions = create_functional_contradictions_for_text(
            text,
            ttl_file.stem,
            hop,
            explanations_by_key,
            global_individuals,
        )
        functional_text = build_functional_violation_text(text, functional_violations)
        (functional_property_output_dir / ttl_file.name).write_text(functional_text, encoding="utf-8")
        total_functional_violations += len(functional_violations)
        functional_questions.extend(questions)

    write_csv(range_questions, range_questions_output_file)
    write_csv(class_questions, class_disjointness_questions_output_file)
    write_csv(functional_questions, functional_property_questions_output_file)
    return (
        len(ttl_files),
        total_range_violations,
        total_class_violations,
        total_functional_violations,
        len(range_questions),
        len(class_questions),
        len(functional_questions),
    )


def existing_file(path: Path, label: str) -> Path:
    if not path.is_file():
        raise FileNotFoundError(f"{label} does not exist: {path}")
    return path


def existing_dir(path: Path, label: str) -> Path:
    if not path.is_dir():
        raise FileNotFoundError(f"{label} does not exist: {path}")
    return path


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Create contradicted TTL files and SPARQL CSVs for range, class-disjointness, and functional-property violations."
    )
    parser.add_argument("--one-hop-input-dir", type=Path, required=True)
    parser.add_argument("--two-hop-input-dir", type=Path, required=True)
    parser.add_argument(
        "--output-root",
        type=Path,
        default=Path("data/resources"),
        help="Root used for TTL output directories when explicit output directories are not provided.",
    )
    parser.add_argument("--one-hop-range-output-dir", type=Path)
    parser.add_argument("--two-hop-range-output-dir", type=Path)
    parser.add_argument("--one-hop-class-disjointness-output-dir", type=Path)
    parser.add_argument("--two-hop-class-disjointness-output-dir", type=Path)
    parser.add_argument("--one-hop-functional-property-output-dir", type=Path)
    parser.add_argument("--two-hop-functional-property-output-dir", type=Path)
    parser.add_argument("--one-hop-explanations", type=Path, required=True)
    parser.add_argument("--two-hop-explanations", type=Path, required=True)
    parser.add_argument("--one-hop-range-questions-output", type=Path, required=True)
    parser.add_argument("--two-hop-range-questions-output", type=Path, required=True)
    parser.add_argument("--one-hop-class-disjointness-questions-output", type=Path)
    parser.add_argument("--two-hop-class-disjointness-questions-output", type=Path)
    parser.add_argument("--one-hop-functional-property-questions-output", type=Path)
    parser.add_argument("--two-hop-functional-property-questions-output", type=Path)
    parser.add_argument(
        "--clean",
        action="store_true",
        help="Remove existing contradiction output directories before regenerating.",
    )
    args = parser.parse_args()

    one_hop_input_dir = existing_dir(args.one_hop_input_dir, "1-hop input directory")
    two_hop_input_dir = existing_dir(args.two_hop_input_dir, "2-hop input directory")
    one_hop_explanations = load_explanations(
        existing_file(args.one_hop_explanations, "1-hop explanations file")
    )
    two_hop_explanations = load_explanations(
        existing_file(args.two_hop_explanations, "2-hop explanations file")
    )

    jobs = [
        (
            "1hop",
            one_hop_input_dir,
            args.one_hop_range_output_dir or args.output_root / "1_hop_range_violation",
            args.one_hop_class_disjointness_output_dir or args.output_root / "1_hop_class_disjointness",
            args.one_hop_functional_property_output_dir or args.output_root / "1_hop_func_prop_violation",
            args.one_hop_range_questions_output,
            args.one_hop_class_disjointness_questions_output
            or args.one_hop_range_questions_output.with_name("SPARQL_questions_class_disjointness.csv"),
            args.one_hop_functional_property_questions_output
            or args.one_hop_range_questions_output.with_name("SPARQL_questions_func_prop_violation.csv"),
            one_hop_explanations,
        ),
        (
            "2hop",
            two_hop_input_dir,
            args.two_hop_range_output_dir or args.output_root / "2_hop_range_violation",
            args.two_hop_class_disjointness_output_dir or args.output_root / "2_hop_class_disjointness",
            args.two_hop_functional_property_output_dir or args.output_root / "2_hop_func_prop_violation",
            args.two_hop_range_questions_output,
            args.two_hop_class_disjointness_questions_output
            or args.two_hop_range_questions_output.with_name("SPARQL_questions_class_disjointness.csv"),
            args.two_hop_functional_property_questions_output
            or args.two_hop_range_questions_output.with_name("SPARQL_questions_func_prop_violation.csv"),
            two_hop_explanations,
        ),
    ]

    for job in jobs:
        for output_dir in job[2:5]:
            if args.clean and output_dir.exists():
                shutil.rmtree(output_dir)

    total_files = 0
    total_range_violations = 0
    total_class_violations = 0
    total_functional_violations = 0
    total_questions = 0
    for (
        hop,
        input_dir,
        range_output_dir,
        class_output_dir,
        functional_output_dir,
        range_questions_output_file,
        class_questions_output_file,
        functional_questions_output_file,
        explanations_by_key,
    ) in jobs:
        (
            file_count,
            range_violation_count,
            class_violation_count,
            functional_violation_count,
            range_question_count,
            class_question_count,
            functional_question_count,
        ) = process_directory(
            input_dir,
            range_output_dir,
            class_output_dir,
            functional_output_dir,
            range_questions_output_file,
            class_questions_output_file,
            functional_questions_output_file,
            hop,
            explanations_by_key,
        )
        total_files += file_count
        total_range_violations += range_violation_count
        total_class_violations += class_violation_count
        total_functional_violations += functional_violation_count
        total_questions += range_question_count + class_question_count + functional_question_count
        print(
            f"{hop}: wrote {file_count} TTL files, "
            f"{range_violation_count} range violations/{range_question_count} questions, "
            f"{class_violation_count} class-disjointness violations/{class_question_count} questions, "
            f"{functional_violation_count} functional-property violations/{functional_question_count} questions"
        )

    print(
        f"Done: {total_files} files, "
        f"{total_range_violations} range violations, "
        f"{total_class_violations} class-disjointness violations, "
        f"{total_functional_violations} functional-property violations, "
        f"{total_questions} questions"
    )


if __name__ == "__main__":
    main()
