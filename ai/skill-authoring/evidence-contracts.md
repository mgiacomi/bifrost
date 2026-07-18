---
audience: bifrost-skill-builder
status: development
applies_to: current-repository-checkout
coverage: source-verified
---

# Evidence Contracts

## Applicability

Use an `evidence_contract` on an LLM-backed YAML skill when selected top-level output claims MUST be supported by successful direct child execution. The contract enforces supportability; it does not prove that a tool result is factually correct, and it is not a workflow, ordering, or dependency language.

Mapped YAML skills MUST NOT declare evidence contracts. An evidence contract requires `output_schema`.

## Manifest shape

Each claim value is one YAML string containing a Boolean expression over exact names from the declaring skill's `allowed_skills`:

```yaml
allowed_skills: [classifyIncident, investigateNetwork, investigateApp]
evidence_contract:
  claims:
    severity: classifyIncident
    likelyCause: classifyIncident and (investigateNetwork or investigateApp)
```

The effective grammar is:

```text
expression     := or_expression EOF
or_expression := and_expression (OR and_expression)*
and_expression := primary (AND primary)*
primary        := SKILL_NAME | "(" expression ")"
```

- `and` binds more tightly than `or`; parentheses override precedence.
- Operators are case-insensitive whole tokens and render canonically as lowercase.
- Skill names are case-sensitive and MUST exactly match a direct `allowed_skills` entry.
- Surrounding and inter-token whitespace is insignificant. Identifiers are never trimmed, repaired, case-folded, truncated, or aliased.
- `and` and `or` are reserved in every casing. Operator substrings in names such as `androidCheck`, `orderLookup`, and `candyParser` remain identifiers.
- `not`, `&&`, `||`, quoted names, implicit operators, predicates, and output-value conditions are unsupported.
- List, object, number, Boolean, null, and blank claim values are invalid; expressions MUST be nonblank YAML strings.

## Truth sets

| Validation point | Names that satisfy references | Claims evaluated |
| --- | --- | --- |
| Plan validation | Nonblank, case-sensitive `PlanTask.capabilityName()` values | Every declared contract claim |
| Final output validation | Direct child names recorded only after successful completion in the current YAML mission | Contract-backed top-level fields present in the schema-valid candidate |

Planned, started, failed, cancelled, merely visible, wrong-case, or blank capability names do not satisfy final validation. Repeated successful calls remain one set member.

An unsupported optional claim may be removed during output retry. A required output claim ultimately fails when its expression remains unsatisfied. Evidence retry uses already completed work and MUST NOT call tools again.

## AND and OR

```yaml
claims:
  category: classifyIncident
  likelyCause: classifyIncident and (investigateNetwork or investigateApp)
```

`likelyCause` succeeds with classification plus either investigator. It fails with classification alone or an investigator alone, and it does not require both investigators.

A failed conjunction reports every still-required clause. A failed disjunction reports that any one alternative can satisfy the group. Prompts and current-version traces retain the canonical expression instead of flattening alternatives into an ambiguous tool list.

## Direct-child and nested boundaries

A reference MUST name a direct child of the skill declaring the contract. It MUST NOT name a grandchild or internal probe.

When a nested YAML child runs, Bifrost saves the parent's successful-direct-skill set, starts the child with an empty set, and restores the parent afterward. The child's internal successes never bubble upward. After the nested child returns successfully, the parent's normal callback/completion boundary credits only that child's public name. A failed nested child receives no parent credit.

Therefore a parent may require `investigateNetwork`, while that child may separately require `checkDns`; the parent cannot require `checkDns` directly.

## Authoring procedure

1. Identify top-level output claims needing deterministic supportability enforcement.
2. List the declaring skill's direct public child names in `allowed_skills`.
3. For each claim, write the smallest expression whose successful children support it.
4. Use `and` for requirements that must all hold and `or` for genuine substitutes.
5. Add parentheses whenever they make mixed precedence explicit.
6. Verify exact casing and direct-child scope.
7. Confirm every declared claim is plan-coverable, including optional output claims.
8. Test success, each missing conjunct, each OR alternative, failure/non-credit, optional-claim omission, and nested isolation.
9. Keep workflow sequencing and multi-intent branch policy in prompts/planning design, not in the evidence expression.

## Diagnostics

Catalog failures identify the resource and `evidence_contract.claims.<claim>` path. Parser and reference failures include a 1-based expression column. A uniquely identifiable casing mistake suggests the exact allowed spelling. Old `tool_evidence` and list-valued claims fail startup; there is no compatibility reader.

At runtime, diagnostics expose the claim, canonical required expression, satisfied direct skills, and structured unsatisfied requirements. Requirement mode `all` means every child clause remains required; `any` means one alternative is sufficient.

## Known limits

Evidence expressions are monotonic and name-only. They do not validate factual correctness, inspect tool output values, enforce call ordering, express negation, select which optional claims the model will emit, or replace RBAC/visibility checks.

## Implementation and test anchors

- [`EvidenceExpression.java`](../../bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/evidence/EvidenceExpression.java) and [`EvidenceExpressionParser.java`](../../bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/evidence/EvidenceExpressionParser.java) define parsing, canonical rendering, evaluation, and structured gaps.
- [`YamlSkillCatalog.java`](../../bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/skill/YamlSkillCatalog.java) compiles once and validates exact direct children.
- [`EvidenceCoverageValidator.java`](../../bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/evidence/EvidenceCoverageValidator.java) defines plan and final truth sets.
- [`CapabilityExecutionRouter.java`](../../bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/core/CapabilityExecutionRouter.java) preserves nested mission isolation.
- [`EvidenceExpressionParserTest.java`](../../bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/runtime/evidence/EvidenceExpressionParserTest.java), [`EvidenceExpressionParserAdditionalTest.java`](../../bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/runtime/evidence/EvidenceExpressionParserAdditionalTest.java), and [`YamlSkillEvidenceExpressionCatalogAdditionalTest.java`](../../bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/skill/YamlSkillEvidenceExpressionCatalogAdditionalTest.java) protect grammar, scalar-shape, exact-name, and direct-child diagnostics.
- [`EvidenceCoverageValidatorTest.java`](../../bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/runtime/evidence/EvidenceCoverageValidatorTest.java), [`EvidencePlanningIntegrationTest.java`](../../bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/runtime/planning/EvidencePlanningIntegrationTest.java), [`SkillAdvisorResolverEvidenceTraceTest.java`](../../bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/chat/SkillAdvisorResolverEvidenceTraceTest.java), and [`NestedSuccessfulSkillBoundaryTest.java`](../../bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/internal/runtime/tool/NestedSuccessfulSkillBoundaryTest.java) protect Boolean truth sets, planning semantics, direct-advisor trace diagnostics, structured gaps, and nested boundary credit.
- The five root manifests under [`bifrost-sample/src/main/resources/skills`](../../bifrost-sample/src/main/resources/skills) demonstrate conjunctions, alternatives, and direct nested-child references.
