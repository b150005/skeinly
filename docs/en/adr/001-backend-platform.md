# ADR-001: Supabase as Backend Platform

## Status

Accepted

## Context

Knit Note's core value proposition is user-to-user sharing and interaction around knitting patterns and progress. This requires a backend platform that provides:

- **Authentication**: User sign-up/sign-in (Google, Apple, email)
- **Database**: Relational data with real-time subscriptions (users, patterns, projects, shares, comments, activity feed)
- **File Storage**: Knitting chart images and progress photos
- **Real-time**: Instant updates when patterns are shared or commented on
- **KMP Compatibility**: First-class Kotlin Multiplatform support for shared module

The data model is inherently relational: users share patterns with other users, comment on projects, and follow activity feeds. These are many-to-many relationships that benefit from SQL joins and row-level security.

## Decision

Use **Supabase** as the backend platform, via the `supabase-kt` library (v3.5.0+).

### Library Coordinates

```kotlin
// BOM
implementation(platform("io.github.jan-tennert.supabase:bom:3.5.0"))

// Modules
implementation("io.github.jan-tennert.supabase:postgrest-kt")   // Database
implementation("io.github.jan-tennert.supabase:auth-kt")         // Authentication
implementation("io.github.jan-tennert.supabase:storage-kt")      // File storage
implementation("io.github.jan-tennert.supabase:realtime-kt")     // Real-time subscriptions
implementation("io.github.jan-tennert.supabase:functions-kt")    // Edge Functions
implementation("io.github.jan-tennert.supabase:compose-auth")    // Compose auth integration
```

### Requirements

- Ktor 3.x (already in project dependencies)
- kotlinx.serialization (already in project dependencies)

## Consequences

### Positive

- **Superior KMP coverage**: supabase-kt v3.5.0 has full API coverage for PostgREST, Auth, Storage, Realtime, and Functions. Firebase's GitLive SDK has significant gaps (Firestore 60%, Storage 40%, Messaging 10%).
- **Relational data model**: PostgreSQL is the natural fit for the Share/Activity/Comment entities with their many-to-many relationships. Firestore's document model would require denormalization and complex subcollection queries.
- **Row-Level Security (RLS)**: Supabase's RLS policies enforce data access rules at the database level (e.g., "users can only see patterns shared with them"), reducing the attack surface compared to client-side Firestore security rules.
- **Compose integrations**: compose-auth and compose-auth-ui provide pre-built authentication UI components for Android.
- **Active development**: supabase-kt is very actively maintained (v3.5.0 released April 2026), with BOM for version management.
- **SQL migrations**: Schema changes are managed via standard SQL migrations, providing version-controlled, reviewable database evolution.
- **Self-hosting option**: Supabase can be self-hosted if needed in the future.

### Negative

- **No built-in push notifications**: Supabase does not include a push notification service. Will need a separate solution (e.g., OneSignal, Firebase Cloud Messaging as standalone, or Supabase Edge Functions + APNs/FCM directly).
- **No built-in analytics/crashlytics**: Will need separate solutions for app analytics and crash reporting.
- **Community SDK**: supabase-kt is a community library, not maintained by Supabase directly (though it is featured in official Supabase documentation).
- **PostgreSQL connection limits**: Need to monitor connection pooling, especially with real-time subscriptions from many concurrent users.

### Neutral

- Push notifications and analytics can be added independently later (e.g., Firebase for FCM/Analytics only, or platform-native solutions).
- Supabase's free tier is sufficient for MVP development and initial user base.

## Alternatives Considered

| Alternative | Pros | Cons | Why Not Chosen |
|------------|------|------|----------------|
| Firebase (GitLive SDK v2.4.0) | Real-time via Firestore listeners, FCM for push notifications, Analytics/Crashlytics built-in | KMP API coverage gaps (Firestore 60%, Storage 40%, Messaging 10%), document model requires denormalization for relational data, community SDK with moderate maintenance | Insufficient KMP coverage for core features; document model is a poor fit for relational social data |
| Custom backend (Ktor server) | Full control, tailored API | Significant development overhead, infrastructure management, delays MVP | Not justified at this stage; Supabase provides all needed capabilities out of the box |
| Appwrite | Open-source BaaS, self-hostable | No established KMP SDK, smaller ecosystem | Lack of KMP support is a blocker |
