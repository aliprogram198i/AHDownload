# AHDownload Architecture

## Product flow

Welcome → Authentication → Home → URL Analysis → Media Result → Format Selection → Download Queue → Background Transfer → History → Smart Studio.

## Boundaries

- `app`: application shell and top-level navigation.
- `core:common`: stable cross-feature primitives.
- `core:designsystem`: AHDownload visual language and reusable interactive components.
- `domain`: platform-independent business rules and value logic.
- `feature/*`: user-facing feature modules. UI owns presentation only.
- `resolver`: URL intelligence, provider adapters, candidate validation and ranking.
- `downloader`: queue, transfer lifecycle, progress and persistence.
- `data/*`: local and remote implementations behind domain contracts.

## Non-negotiable rules

1. UI never contains resolver or transfer logic.
2. Resolver never owns Android UI state.
3. Download execution is independent from any Activity or Composable lifecycle.
4. Provider-specific behavior is isolated behind adapters.
5. Secrets and provider credentials are never embedded in the APK.
6. Download state is modeled as an explicit state machine.
7. New providers must not require rewriting the core download engine.
8. Video splitting is intentionally outside the product scope.

## Delivery gates

Every architectural increment must compile, pass unit tests, and pass the Android CI verification before it is treated as a completed foundation change.
