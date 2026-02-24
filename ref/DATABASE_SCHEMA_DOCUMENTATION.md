# Parcera Platform - Complete Database Schema Documentation

> **Purpose:** Comprehensive documentation of all database schemas, tables, columns, and service interactions  
> **Last Updated:** February 23, 2026  
> **Database:** PostgreSQL 13+ (identity_db)

---

## Table of Contents

1. [Overview](#1-overview)
2. [Schema Architecture](#2-schema-architecture)
3. [Public Schema (Shared)](#3-public-schema-shared)
4. [Tenant Schema (Multi-Tenant Isolation)](#4-tenant-schema-multi-tenant-isolation)
5. [Service Integration](#5-service-integration)
6. [Merchant Onboarding Flow](#6-merchant-onboarding-flow)
7. [Table Creation Timeline](#7-table-creation-timeline)
8. [Read/Write Operations Matrix](#8-readwrite-operations-matrix)
9. [Dependencies & Foreign Keys](#9-dependencies--foreign-keys)
10. [Migration Strategy](#10-migration-strategy)

---

## 1. Overview

### Multi-Tenancy Architecture

Parcera uses **PostgreSQL schema-based multi-tenancy** for data isolation:

```
identity_db
├── public schema (shared data)
│   └── users, tenants, sessions, permissions, voice_library, etc.
│
├── tenant_simply_south (tenant-specific data)
│   └── merchants, locations, agents, phone_numbers, orders, reservations, etc.
│
├── tenant_pizza_palace (another tenant)
│   └── merchants, locations, agents, phone_numbers, orders, reservations, etc.
│
└── tenant_salon_xyz (another tenant)
    └── merchants, locations, agents, phone_numbers, orders, reservations, etc.
```

**Benefits:**
- ✅ Strong data isolation (no cross-tenant queries)
- ✅ Row-level security via PostgreSQL schemas
- ✅ Independent backups per tenant
- ✅ Scalable to 1000+ tenants

---

## 2. Schema Architecture

### Schema Naming Convention

```
tenant_{slug}
```

**Examples:**
- Slug: `simply-south` → Schema: `tenant_simply_south`
- Slug: `pizza-palace` → Schema: `tenant_pizza_palace`
- Slug: `nyc-salon` → Schema: `tenant_nyc_salon`

**Rules:**
- Must start with `tenant_`
- Followed by lowercase letters, digits, or underscores
- Max 63 characters (PostgreSQL limit)
- Generated from tenant slug (hyphens → underscores)

---

## 3. Public Schema (Shared)

### 3.1 Overview

**Purpose:** Store data shared across all tenants (users, authentication, permissions, global settings)

**Managed By:** Identity Service

**Total Tables:** 18

---

### 3.2 Table Definitions

#### `users`

**Purpose:** User accounts (staff, managers, admins, salespeople)

```sql
CREATE TABLE "public"."users" (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email               VARCHAR(255) NOT NULL UNIQUE,
    first_name          VARCHAR(100),
    last_name           VARCHAR(100),
    phone               VARCHAR(20),
    physical_id         VARCHAR(255),              -- Physical badge/card ID
    created_at          TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP NOT NULL DEFAULT NOW()
);
```

**Indexes:**
- `users_email_idx` (UNIQUE)
- `users_physical_id_idx`

**Created By:** Identity Service (POST /auth/register)

**Read By:** Identity Service, Conversation Engine (for audit logs)

**Write By:** Identity Service

---

#### `tenants`

**Purpose:** Top-level tenant organizations

```sql
CREATE TABLE "public"."tenants" (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name                VARCHAR(255) NOT NULL,          -- "Simply South Restaurant Group"
    slug                VARCHAR(100) NOT NULL UNIQUE,  -- "simply-south"
    schema_name         VARCHAR(100) NOT NULL UNIQUE,  -- "tenant_simply_south"
    type                VARCHAR(50) NOT NULL,           -- 'enterprise' | 'standard' | 'trial'
    contact_email       VARCHAR(255),
    contact_phone       VARCHAR(20),
    status              VARCHAR(50) NOT NULL DEFAULT 'active',  -- 'active' | 'suspended' | 'deleted'
    created_by          UUID NOT NULL REFERENCES users(id),
    created_at          TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP NOT NULL DEFAULT NOW()
);
```

**Indexes:**
- `tenants_slug_idx` (UNIQUE)
- `tenants_schema_name_idx` (UNIQUE)
- `tenants_status_idx`
- `tenants_created_by_idx`

**Created By:** Identity Service (POST /tenants)

**Read By:** Identity Service, Conversation Engine

**Write By:** Identity Service

---

#### `sessions`

**Purpose:** User sessions (Keycloak integration)

```sql
CREATE TABLE "public"."sessions" (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    keycloak_session_id VARCHAR(255) NOT NULL UNIQUE,
    access_token        TEXT,
    refresh_token       TEXT,
    expires_at          TIMESTAMP NOT NULL,
    created_at          TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP NOT NULL DEFAULT NOW()
);
```

**Indexes:**
- `sessions_user_id_idx`
- `sessions_keycloak_session_id_idx` (UNIQUE)

**Created By:** Identity Service (POST /auth/login)

**Read By:** Identity Service

**Write By:** Identity Service

---

#### `devices`

**Purpose:** Trusted devices (POS terminals, tablets)

```sql
CREATE TABLE "public"."devices" (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             UUID REFERENCES users(id) ON DELETE CASCADE,
    tenant_id           UUID REFERENCES tenants(id) ON DELETE CASCADE,
    device_name         VARCHAR(255),
    device_id           VARCHAR(255) NOT NULL,
    device_type         VARCHAR(50),                    -- 'pos' | 'tablet' | 'mobile'
    last_ip             VARCHAR(50),
    last_used_at        TIMESTAMP,
    is_trusted          BOOLEAN NOT NULL DEFAULT false,
    created_at          TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP NOT NULL DEFAULT NOW()
);
```

**Indexes:**
- `devices_user_id_idx`
- `devices_tenant_id_idx`
- `devices_device_id_idx`

**Created By:** Identity Service (POST /devices)

**Read By:** Identity Service

**Write By:** Identity Service

---

#### `user_roles`

**Purpose:** User role assignments (tenant-scoped)

```sql
CREATE TABLE "public"."user_roles" (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id                 UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    tenant_id               UUID REFERENCES tenants(id) ON DELETE CASCADE,
    role_type               VARCHAR(50) NOT NULL,  -- 'admin' | 'manager' | 'staff' | 'salesperson'
    role_level              INTEGER,
    pin_hash                VARCHAR(255),          -- For manager PIN authentication
    pin_attempts            INTEGER NOT NULL DEFAULT 0,
    pin_locked_until        TIMESTAMP,
    manager_override_pin_hash VARCHAR(255),
    created_at              TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMP NOT NULL DEFAULT NOW()
);
```

**Indexes:**
- `user_roles_user_id_idx`
- `user_roles_tenant_id_idx`

**Created By:** Identity Service (POST /users/:id/roles)

**Read By:** Identity Service

**Write By:** Identity Service

---

#### `permissions`

**Purpose:** System permissions (feature-action pairs)

```sql
CREATE TABLE "public"."permissions" (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    feature_name        VARCHAR(100) NOT NULL,  -- 'orders' | 'reservations' | 'reports'
    action              VARCHAR(50) NOT NULL,   -- 'read' | 'write' | 'delete'
    description         TEXT
);
```

**Unique Constraint:** `(feature_name, action)`

**Created By:** Identity Service (seed data)

**Read By:** Identity Service

**Write By:** Identity Service (via migrations)

---

#### `role_permissions`

**Purpose:** Map permissions to roles

```sql
CREATE TABLE "public"."role_permissions" (
    role_type           VARCHAR(50) NOT NULL,  -- 'admin' | 'manager' | 'staff'
    permission_id       UUID NOT NULL REFERENCES permissions(id) ON DELETE CASCADE,
    PRIMARY KEY (role_type, permission_id)
);
```

**Created By:** Identity Service (seed data)

**Read By:** Identity Service

**Write By:** Identity Service

---

#### `custom_roles`

**Purpose:** Tenant-specific custom roles

```sql
CREATE TABLE "public"."custom_roles" (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    name                VARCHAR(100) NOT NULL,
    description         TEXT,
    base_role           VARCHAR(50),  -- 'staff' | 'manager'
    is_active           BOOLEAN NOT NULL DEFAULT true,
    created_at          TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP NOT NULL DEFAULT NOW()
);
```

**Indexes:**
- `custom_roles_tenant_id_idx`

**Created By:** Identity Service (POST /custom-roles)

**Read By:** Identity Service

**Write By:** Identity Service

---

#### `custom_role_permissions`

**Purpose:** Map permissions to custom roles

```sql
CREATE TABLE "public"."custom_role_permissions" (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    custom_role_id      UUID NOT NULL REFERENCES custom_roles(id) ON DELETE CASCADE,
    permission_id       UUID NOT NULL REFERENCES permissions(id) ON DELETE CASCADE,
    created_at          TIMESTAMP NOT NULL DEFAULT NOW()
);
```

**Indexes:**
- `custom_role_permissions_custom_role_id_idx`
- `custom_role_permissions_permission_id_idx`

**Created By:** Identity Service (POST /custom-roles/:id/permissions)

**Read By:** Identity Service

**Write By:** Identity Service

---

#### `activation_codes`

**Purpose:** Merchant activation codes (for onboarding)

```sql
CREATE TABLE "public"."activation_codes" (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code                VARCHAR(50) NOT NULL UNIQUE,    -- "SIMPLY-ABC123"
    code_type           VARCHAR(50) NOT NULL,           -- 'merchant' | 'salesperson'
    batch_id            UUID REFERENCES activation_batches(id),
    tenant_id           UUID REFERENCES tenants(id),
    physical_id         VARCHAR(255),                   -- Physical card/badge ID
    status              VARCHAR(50) NOT NULL DEFAULT 'active',  -- 'active' | 'used' | 'expired'
    used_by             UUID REFERENCES users(id),
    used_at             TIMESTAMP,
    expires_at          TIMESTAMP,
    created_at          TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP NOT NULL DEFAULT NOW()
);
```

**Indexes:**
- `activation_codes_code_idx` (UNIQUE)
- `activation_codes_tenant_id_idx`
- `activation_codes_batch_id_idx`

**Created By:** Identity Service (POST /activation-codes/batch)

**Read By:** Identity Service

**Write By:** Identity Service

---

#### `activation_batches`

**Purpose:** Track bulk activation code generation

```sql
CREATE TABLE "public"."activation_batches" (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    created_by          UUID NOT NULL REFERENCES users(id),
    quantity            INTEGER NOT NULL,
    purpose             TEXT,
    status              VARCHAR(50) NOT NULL DEFAULT 'active',
    csv_url             TEXT,                          -- S3 URL for batch CSV
    created_at          TIMESTAMP NOT NULL DEFAULT NOW()
);
```

**Created By:** Identity Service (POST /activation-codes/batch)

**Read By:** Identity Service

**Write By:** Identity Service

---

#### `invitations`

**Purpose:** User invitations (cross-tenant reference)

**Note:** Moved to tenant schemas (see section 4)

---

#### `audit_events`

**Purpose:** System-wide audit log

```sql
CREATE TABLE "public"."audit_events" (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID REFERENCES tenants(id),
    user_id             UUID REFERENCES users(id),
    event_type          VARCHAR(100) NOT NULL,          -- 'user.created' | 'tenant.created'
    resource_type       VARCHAR(50),                    -- 'user' | 'tenant' | 'merchant'
    resource_id         UUID,
    action              VARCHAR(50) NOT NULL,           -- 'create' | 'update' | 'delete'
    old_value           JSONB,
    new_value           JSONB,
    ip_address          VARCHAR(50),
    user_agent          TEXT,
    created_at          TIMESTAMP NOT NULL DEFAULT NOW()
);
```

**Indexes:**
- `audit_events_tenant_id_idx`
- `audit_events_user_id_idx`
- `audit_events_created_at_idx`
- `audit_events_event_type_idx`

**Created By:** Identity Service (automatic trigger)

**Read By:** Identity Service (audit API)

**Write By:** Identity Service

---

#### `verification_requests`

**Purpose:** Email/phone verification codes

```sql
CREATE TABLE "public"."verification_requests" (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             UUID REFERENCES users(id) ON DELETE CASCADE,
    type                VARCHAR(50) NOT NULL,           -- 'email' | 'phone'
    contact             VARCHAR(255) NOT NULL,
    code                VARCHAR(10) NOT NULL,
    verified            BOOLEAN NOT NULL DEFAULT false,
    expires_at          TIMESTAMP NOT NULL,
    attempts            INTEGER NOT NULL DEFAULT 0,
    max_attempts        INTEGER NOT NULL DEFAULT 3,
    created_at          TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP NOT NULL DEFAULT NOW()
);
```

**Indexes:**
- `verification_requests_user_id_idx`
- `verification_requests_code_idx`

**Created By:** Identity Service (POST /auth/verify/send)

**Read By:** Identity Service

**Write By:** Identity Service

---

#### `recovery_requests`

**Purpose:** Password/account recovery requests

```sql
CREATE TABLE "public"."recovery_requests" (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             UUID REFERENCES users(id) ON DELETE CASCADE,
    type                VARCHAR(50) NOT NULL,           -- 'password' | 'account'
    token               VARCHAR(255) NOT NULL UNIQUE,
    status              VARCHAR(50) NOT NULL DEFAULT 'pending',
    expires_at          TIMESTAMP NOT NULL,
    completed_at        TIMESTAMP,
    created_at          TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP NOT NULL DEFAULT NOW()
);
```

**Indexes:**
- `recovery_requests_token_idx` (UNIQUE)
- `recovery_requests_user_id_idx`

**Created By:** Identity Service (POST /auth/recover)

**Read By:** Identity Service

**Write By:** Identity Service

---

#### `voice_library`

**Purpose:** Shared voice library (ElevenLabs voices)

```sql
CREATE TABLE "public"."voice_library" (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    provider                VARCHAR(50) NOT NULL,           -- 'elevenlabs' | 'google' | 'azure'
    provider_voice_id       VARCHAR(255) NOT NULL,          -- ElevenLabs voice ID
    name                    VARCHAR(255) NOT NULL,
    description             TEXT,
    preview_url             TEXT,
    category                VARCHAR(50),                    -- 'conversational' | 'narration'
    gender                  VARCHAR(50),                    -- 'male' | 'female' | 'neutral'
    age                     VARCHAR(50),                    -- 'young' | 'middle_aged' | 'old'
    accent                  VARCHAR(100),                   -- 'american' | 'british' | 'indian'
    verified_languages      JSONB NOT NULL DEFAULT '[]',
    provider_metadata       JSONB,
    is_available            BOOLEAN NOT NULL DEFAULT true,
    deactivated_at          TIMESTAMP,
    deactivation_reason     TEXT,
    synced_at               TIMESTAMP NOT NULL DEFAULT NOW(),
    created_at              TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMP NOT NULL DEFAULT NOW()
);
```

**Unique Constraint:** `(provider, provider_voice_id)`

**Indexes:**
- `voice_library_provider_idx`
- `voice_library_category_idx`
- `voice_library_is_available_idx`

**Created By:** Identity Service (sync job from ElevenLabs)

**Read By:** Identity Service, Conversation Engine

**Write By:** Identity Service

---

#### `api_keys`

**Purpose:** API authentication tokens

```sql
CREATE TABLE "public"."api_keys" (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    key_hash            VARCHAR(255) NOT NULL UNIQUE,
    key_prefix          VARCHAR(20) NOT NULL,           -- "pk_live_" or "pk_test_"
    tenant_id           UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    name                VARCHAR(255) NOT NULL,
    scopes              JSONB DEFAULT '[]',
    is_active           BOOLEAN NOT NULL DEFAULT true,
    expires_at          TIMESTAMP,
    last_used_at        TIMESTAMP,
    created_by          UUID REFERENCES users(id),
    created_at          TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP NOT NULL DEFAULT NOW()
);
```

**Indexes:**
- `api_keys_key_hash_idx` (UNIQUE)
- `api_keys_tenant_id_idx`

**Created By:** Identity Service (POST /api-keys)

**Read By:** Identity Service, API Gateway

**Write By:** Identity Service

---

#### `phone_index`

**Purpose:** Global phone number lookup index (O(1) tenant resolution)

```sql
CREATE TABLE "public"."phone_index" (
    phone_number        VARCHAR(20) PRIMARY KEY,        -- E.164 format: +14432429429
    tenant_id           UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    schema_name         VARCHAR(100) NOT NULL,          -- "tenant_simply_south"
    location_id         UUID NOT NULL,                  -- UUID from tenant schema
    created_at          TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP NOT NULL DEFAULT NOW()
);
```

**Indexes:**
- `phone_index_phone_number_idx` (PRIMARY KEY)
- `phone_index_tenant_id_idx`
- `phone_index_schema_name_idx`

**Created By:** Conversation Engine (POST /.../phone-numbers)

**Read By:** IVR Service (agent.py - config resolution)

**Write By:** Conversation Engine

---

### 3.3 Public Schema Summary

**Total Tables:** 18

**Purpose:** Global/Shared Data

**Services:**
- ✅ Identity Service (primary)
- ✅ Conversation Engine (reads voice_library, phone_index)
- ✅ IVR Service (reads phone_index)

---

## 4. Tenant Schema (Multi-Tenant Isolation)

### 4.1 Overview

**Purpose:** Isolate tenant data for security and scalability

**Schema Pattern:** `tenant_{slug}` (e.g., `tenant_simply_south`)

**Total Tables:** 13

**Managed By:** Identity Service (creation), Conversation Engine (CRUD operations)

---

### 4.2 Table Definitions

#### `merchants`

**Purpose:** Business entities within a tenant

```sql
CREATE TABLE "{schema}".merchants (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name                VARCHAR(255) NOT NULL,          -- "Simply South - Downtown"
    slug                VARCHAR(100) NOT NULL UNIQUE,  -- "downtown-branch"
    tenant_id           UUID,                           -- References public.tenants(id)
    business_type       VARCHAR(50) NOT NULL,           -- 'restaurant' | 'salon' | 'clinic'
    contact_email       VARCHAR(255),
    contact_phone       VARCHAR(20),
    address             TEXT,
    status              VARCHAR(50) NOT NULL DEFAULT 'active',
    created_by          UUID NOT NULL,                  -- References public.users(id)
    created_at          TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP NOT NULL DEFAULT NOW()
);
```

**Indexes:**
- `{schema}_merchants_tenant_id_idx`
- `{schema}_merchants_status_idx`
- `{schema}_merchants_slug_idx`

**Created By:** Identity Service (POST /merchants)

**Read By:** Identity Service, Conversation Engine

**Write By:** Identity Service

---

#### `locations`

**Purpose:** Physical locations for a merchant

```sql
CREATE TABLE "{schema}".locations (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    merchant_id             UUID NOT NULL REFERENCES "{schema}".merchants(id) ON DELETE CASCADE,
    name                    VARCHAR(255) NOT NULL,      -- "Downtown Branch"
    address                 TEXT,
    timezone                VARCHAR(50) NOT NULL DEFAULT 'UTC',
    status                  VARCHAR(50) NOT NULL DEFAULT 'active',
    is_open                 BOOLEAN NOT NULL DEFAULT false,
    operating_hours         TEXT,                       -- JSONB string or plain text
    reservation_provider    VARCHAR(50) DEFAULT 'toast_tables',
    reservation_provider_config JSONB DEFAULT '{}',
    business_domain         VARCHAR(50) DEFAULT 'general',
    tenant_id               UUID,
    schema_name             VARCHAR(100),               -- "tenant_simply_south"
    provider_location_id    VARCHAR(255),               -- Toast GUID or external provider ID
    created_at              TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMP NOT NULL DEFAULT NOW()
);
```

**Foreign Keys:**
- `merchant_id` → `{schema}.merchants(id)` CASCADE

**Indexes:**
- `{schema}_locations_merchant_id_idx`
- `{schema}_locations_status_idx`
- `{schema}_locations_tenant_id_idx`
- `{schema}_locations_schema_name_idx`
- `{schema}_locations_provider_location_id_idx`

**Created By:** Identity Service (POST /locations)

**Read By:** Identity Service, Conversation Engine, IVR Service

**Write By:** Identity Service, Conversation Engine

---

#### `invitations`

**Purpose:** Location-specific user invitations

```sql
CREATE TABLE "{schema}".invitations (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email               VARCHAR(255) NOT NULL,
    type                VARCHAR(50) NOT NULL,           -- 'staff' | 'manager'
    tenant_id           UUID,
    merchant_id         UUID,
    location_id         UUID,
    role                VARCHAR(50),
    custom_role_id      UUID,
    invited_by          UUID NOT NULL,                  -- References public.users(id)
    token               VARCHAR(255) NOT NULL UNIQUE,
    status              VARCHAR(50) NOT NULL DEFAULT 'pending',
    expires_at          TIMESTAMP NOT NULL,
    accepted_at         TIMESTAMP,
    accepted_by         UUID,
    created_at          TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP NOT NULL DEFAULT NOW()
);
```

**Indexes:**
- `{schema}_invitations_email_idx`
- `{schema}_invitations_token_idx`
- `{schema}_invitations_status_idx`

**Created By:** Identity Service (POST /invitations)

**Read By:** Identity Service

**Write By:** Identity Service

---

#### `agents`

**Purpose:** Voice AI agents (1 per location)

```sql
CREATE TABLE "{schema}".agents (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    location_id             UUID NOT NULL REFERENCES "{schema}".locations(id) ON DELETE CASCADE,
    provider                VARCHAR(50) NOT NULL,       -- 'elevenlabs' | 'parcera_voice'
    agent_name              VARCHAR(255) NOT NULL,
    agent_id_external       VARCHAR(255),               -- ElevenLabs agent ID
    first_message           TEXT NOT NULL,
    system_prompt           TEXT NOT NULL,
    voice_id                VARCHAR(255) NOT NULL,
    language                VARCHAR(10) DEFAULT 'en',
    llm_model               VARCHAR(100) DEFAULT 'gpt-4o-mini',
    temperature             DECIMAL(2,1) DEFAULT 0.7,
    fallback_phone          VARCHAR(20),
    is_active               BOOLEAN DEFAULT true,
    supported_languages     VARCHAR(5)[] DEFAULT ARRAY['en']::VARCHAR(5)[],
    primary_language        VARCHAR(5) DEFAULT 'en',
    first_message_en        TEXT,
    first_message_es        TEXT,
    voice_library_id        UUID,                       -- References public.voice_library(id)
    created_at              TIMESTAMP DEFAULT NOW(),
    updated_at              TIMESTAMP DEFAULT NOW()
);
```

**Foreign Keys:**
- `location_id` → `{schema}.locations(id)` CASCADE

**Unique Constraint:** `(location_id, agent_name)`

**Indexes:**
- `{schema}_agents_location_id_idx`
- `{schema}_agents_provider_idx`
- `{schema}_agents_is_active_idx`

**Created By:** Conversation Engine (POST /.../agents)

**Read By:** Conversation Engine, IVR Service

**Write By:** Conversation Engine

---

#### `tool_registry`

**Purpose:** Custom webhook tools for agents

```sql
CREATE TABLE "{schema}".tool_registry (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    location_id                 UUID NOT NULL REFERENCES "{schema}".locations(id) ON DELETE CASCADE,
    name                        VARCHAR(100) NOT NULL,
    provider                    VARCHAR(50) NOT NULL,
    scheme                      VARCHAR(50),
    external_id                 VARCHAR(255),           -- ElevenLabs tool ID
    category                    VARCHAR(100),
    description                 TEXT NOT NULL,
    parameters_schema           JSONB NOT NULL,
    is_active                   BOOLEAN DEFAULT true,
    config_version              INTEGER NOT NULL,
    generated_from_config_id    UUID NOT NULL,
    lifecycle_status            VARCHAR(50) DEFAULT 'active',
    created_by                  UUID,
    created_at                  TIMESTAMP DEFAULT NOW()
);
```

**Foreign Keys:**
- `location_id` → `{schema}.locations(id)` CASCADE

**Indexes:**
- `{schema}_tool_registry_location_id_idx`
- `{schema}_tool_registry_lifecycle_idx`

**Created By:** Conversation Engine (auto-generated during agent creation)

**Read By:** Conversation Engine

**Write By:** Conversation Engine

---

#### `agent_tools`

**Purpose:** Junction table (agents ↔ tools)

```sql
CREATE TABLE "{schema}".agent_tools (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    agent_id            UUID NOT NULL REFERENCES "{schema}".agents(id) ON DELETE CASCADE,
    tool_registry_id    UUID NOT NULL REFERENCES "{schema}".tool_registry(id) ON DELETE CASCADE,
    is_active           BOOLEAN DEFAULT true,
    created_at          TIMESTAMP DEFAULT NOW()
);
```

**Foreign Keys:**
- `agent_id` → `{schema}.agents(id)` CASCADE
- `tool_registry_id` → `{schema}.tool_registry(id)` CASCADE

**Unique Constraint:** `(agent_id, tool_registry_id)`

**Indexes:**
- `{schema}_agent_tools_agent_id_idx`
- `{schema}_agent_tools_tool_id_idx`

**Created By:** Conversation Engine (auto during agent creation)

**Read By:** Conversation Engine

**Write By:** Conversation Engine

---

#### `knowledge_bases`

**Purpose:** AI knowledge/context data (1 per location)

```sql
CREATE TABLE "{schema}".knowledge_bases (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    location_id         UUID NOT NULL UNIQUE REFERENCES "{schema}".locations(id) ON DELETE CASCADE,
    name                VARCHAR(255) NOT NULL,
    entries             JSONB DEFAULT '[]',             -- Knowledge entries array
    entries_hash        VARCHAR(64) NOT NULL,
    external_id         VARCHAR(255),                   -- ElevenLabs knowledge base ID
    provider            VARCHAR(50) NOT NULL,
    sync_status         VARCHAR(50) DEFAULT 'pending',
    sync_error          TEXT,
    last_synced_at      TIMESTAMP,
    is_active           BOOLEAN DEFAULT true,
    agent_id            UUID UNIQUE,                    -- Linked agent
    created_at          TIMESTAMP DEFAULT NOW(),
    updated_at          TIMESTAMP DEFAULT NOW()
);
```

**Foreign Keys:**
- `location_id` → `{schema}.locations(id)` CASCADE

**Indexes:**
- `{schema}_knowledge_bases_sync_status_idx`

**Created By:** Conversation Engine (POST /.../knowledge-base)

**Read By:** Conversation Engine, IVR Service

**Write By:** Conversation Engine

---

#### `location_reservation_config`

**Purpose:** Reservation provider configuration

```sql
CREATE TABLE "{schema}".location_reservation_config (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    location_id             UUID NOT NULL REFERENCES "{schema}".locations(id) ON DELETE CASCADE,
    provider                VARCHAR(50) NOT NULL,       -- 'toast_tables' | 'parcera_tables'
    provider_location_id    VARCHAR(255),               -- Toast GUID
    provider_config         JSONB NOT NULL DEFAULT '{}',
    business_name           VARCHAR(255),
    timezone                VARCHAR(50) NOT NULL DEFAULT 'UTC',
    max_party_size          INTEGER,
    min_party_size          INTEGER,
    waitlist_enabled        BOOLEAN NOT NULL DEFAULT false,
    fetched_at              TIMESTAMP NOT NULL DEFAULT NOW(),
    is_active               BOOLEAN NOT NULL DEFAULT true,
    created_at              TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMP NOT NULL DEFAULT NOW()
);
```

**Foreign Keys:**
- `location_id` → `{schema}.locations(id)` CASCADE

**Indexes:**
- `{schema}_loc_res_config_location_idx`
- `{schema}_loc_res_config_provider_idx`
- `{schema}_loc_res_config_provider_loc_idx`

**Created By:** Conversation Engine (fetched from Toast Tables API)

**Read By:** Conversation Engine, IVR Service

**Write By:** Conversation Engine

---

#### `customers`

**Purpose:** Customer identity (caller history)

```sql
CREATE TABLE "{schema}".customers (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    location_id             UUID NOT NULL REFERENCES "{schema}".locations(id) ON DELETE CASCADE,
    phone                   VARCHAR(20),
    phone_hash              VARCHAR(64),                -- SHA256 hash for privacy
    name                    VARCHAR(255),
    email                   VARCHAR(255),
    last_device_id          VARCHAR(255),
    conversation_count      INTEGER DEFAULT 1,
    last_seen_at            TIMESTAMP DEFAULT NOW(),
    consent_given           BOOLEAN DEFAULT false,
    deleted_at              TIMESTAMP,
    created_at              TIMESTAMP DEFAULT NOW(),
    updated_at              TIMESTAMP DEFAULT NOW()
);
```

**Foreign Keys:**
- `location_id` → `{schema}.locations(id)` CASCADE

**Unique Constraint:** `(location_id, phone_hash)`

**Indexes:**
- `{schema}_customers_location_id_idx`
- `{schema}_customers_phone_hash_idx`
- `{schema}_customers_email_idx`

**Created By:** Conversation Engine (auto during first call)

**Read By:** Conversation Engine

**Write By:** Conversation Engine

---

#### `call_logs`

**Purpose:** Call history and analytics

```sql
CREATE TABLE "{schema}".call_logs (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    location_id             UUID NOT NULL REFERENCES "{schema}".locations(id) ON DELETE CASCADE,
    agent_id                UUID NOT NULL REFERENCES "{schema}".agents(id) ON DELETE CASCADE,
    conversation_id         VARCHAR(255) NOT NULL UNIQUE,   -- IVR session ID
    started_at              TIMESTAMP NOT NULL,
    ended_at                TIMESTAMP,
    duration_seconds        INTEGER,
    call_type               VARCHAR(50) NOT NULL,           -- 'inbound_pstn' | 'inbound_voip' | 'outbound'
    provider                VARCHAR(50) NOT NULL,           -- 'elevenlabs' | 'parcera_voice'
    status                  VARCHAR(50) DEFAULT 'in_progress',
    call_outcome            VARCHAR(50),
    reservation_id          VARCHAR(255),
    source                  VARCHAR(50),                    -- 'pstn' | 'voip'
    elevenlabs_user_id      VARCHAR(100),
    identity_type           VARCHAR(50),
    device_id               VARCHAR(100),
    phone_hash              VARCHAR(64),
    customer_id             UUID REFERENCES "{schema}".customers(id) ON DELETE SET NULL,
    order_id                UUID,                           -- References orders(id)
    ivr_reservation_id      UUID,                           -- References reservations(id)
    sip_call_id             VARCHAR(255),
    created_at              TIMESTAMP DEFAULT NOW(),
    updated_at              TIMESTAMP DEFAULT NOW()
);
```

**Foreign Keys:**
- `location_id` → `{schema}.locations(id)` CASCADE
- `agent_id` → `{schema}.agents(id)` CASCADE
- `customer_id` → `{schema}.customers(id)` SET NULL

**Unique Constraint:** `conversation_id`

**Indexes:**
- `{schema}_call_logs_location_id_idx`
- `{schema}_call_logs_agent_id_idx`
- `{schema}_call_logs_started_at_idx`
- `{schema}_call_logs_status_idx`
- `{schema}_call_logs_device_id_idx`
- `{schema}_call_logs_phone_hash_idx`

**Created By:** Conversation Engine (webhook handler), IVR Service (agent.py)

**Read By:** Conversation Engine

**Write By:** Conversation Engine, IVR Service

---

#### `phone_numbers`

**Purpose:** PSTN phone numbers (Twilio + ElevenLabs)

```sql
CREATE TABLE "{schema}".phone_numbers (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    location_id                 UUID NOT NULL REFERENCES "{schema}".locations(id) ON DELETE CASCADE,
    agent_id                    VARCHAR(255),           -- Linked agent (external ID)
    phone_number                VARCHAR(20) NOT NULL,   -- E.164: +14432429429
    label                       VARCHAR(255),
    provider                    VARCHAR(50) NOT NULL DEFAULT 'twilio',
    elevenlabs_phone_number_id  VARCHAR(255),
    twilio_phone_sid            VARCHAR(50),
    twilio_account_sid          VARCHAR(50),
    ivr_provider                VARCHAR(50),            -- 'elevenlabs' | 'parcera_voice'
    sip_trunk_id                VARCHAR(255),           -- LiveKit outbound trunk
    sip_inbound_trunk_id        VARCHAR(255),           -- LiveKit inbound trunk
    sip_outbound_trunk_id       VARCHAR(255),           -- LiveKit outbound trunk
    sip_dispatch_rule_id        VARCHAR(255),           -- LiveKit dispatch rule
    sip_auth_username           VARCHAR(255),
    sip_auth_password           VARCHAR(255),           -- Encrypted
    twilio_trunk_sid            VARCHAR(255),
    capabilities                JSONB DEFAULT '{"inbound": true, "outbound": true}',
    is_active                   BOOLEAN NOT NULL DEFAULT true,
    status                      VARCHAR(20) NOT NULL DEFAULT 'in-use',
    cooldown_until              TIMESTAMP,
    last_assigned_at            TIMESTAMP,
    assignment_count            INTEGER NOT NULL DEFAULT 1,
    created_at                  TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at                  TIMESTAMP NOT NULL DEFAULT NOW()
);
```

**Foreign Keys:**
- `location_id` → `{schema}.locations(id)` CASCADE

**Unique Constraint:** `(location_id, phone_number)`

**Indexes:**
- `{schema}_phone_numbers_location_id_idx`
- `{schema}_phone_numbers_agent_id_idx`
- `{schema}_phone_numbers_phone_number_idx`
- `{schema}_phone_numbers_11labs_id_idx`
- `{schema}_phone_numbers_is_active_idx`
- `{schema}_phone_numbers_status_idx`
- `{schema}_phone_numbers_cooldown_idx`

**Created By:** Conversation Engine (POST /.../phone-numbers), IVR Service (POST /onboard-phone)

**Read By:** Conversation Engine, IVR Service

**Write By:** Conversation Engine, IVR Service

---

#### `reservation_mirror`

**Purpose:** Map customer phones to provider reservation IDs

```sql
CREATE TABLE "{schema}".reservation_mirror (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    location_id                 UUID NOT NULL REFERENCES "{schema}".locations(id) ON DELETE CASCADE,
    provider                    VARCHAR(50) NOT NULL,       -- 'toast_tables'
    provider_reservation_id     VARCHAR(255) NOT NULL,      -- Toast GUID
    customer_phone              VARCHAR(20) NOT NULL,       -- E.164
    customer_email              VARCHAR(255),
    customer_name               VARCHAR(255),
    party_size                  INTEGER,
    reservation_date            VARCHAR(10),                -- YYYY-MM-DD
    reservation_time            VARCHAR(5),                 -- HH:MM
    status                      VARCHAR(50) NOT NULL DEFAULT 'confirmed',
    created_at                  TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at                  TIMESTAMP NOT NULL DEFAULT NOW()
);
```

**Foreign Keys:**
- `location_id` → `{schema}.locations(id)` CASCADE

**Indexes:**
- `{schema}_res_mirror_phone_idx`
- `{schema}_res_mirror_provider_res_idx`
- `{schema}_res_mirror_loc_phone_idx`
- `{schema}_res_mirror_status_idx`

**Created By:** Conversation Engine (POST /.../reservations)

**Read By:** Conversation Engine

**Write By:** Conversation Engine

---

#### `orders`

**Purpose:** IVR food/delivery orders

```sql
CREATE TABLE "{schema}".orders (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    location_id         UUID NOT NULL REFERENCES "{schema}".locations(id) ON DELETE CASCADE,
    session_id          VARCHAR(255) NOT NULL UNIQUE,
    order_number        VARCHAR(50) NOT NULL,           -- "ORD-ABC-1234"
    order_type          VARCHAR(20) NOT NULL,           -- 'delivery' | 'pickup'
    customer_name       VARCHAR(255),
    customer_phone      VARCHAR(20),
    delivery_address    TEXT,
    cart_items          JSONB NOT NULL DEFAULT '[]',
    subtotal            DECIMAL(10,2) NOT NULL DEFAULT 0,
    tax                 DECIMAL(10,2) NOT NULL DEFAULT 0,
    delivery_fee        DECIMAL(10,2) NOT NULL DEFAULT 0,
    total               DECIMAL(10,2) NOT NULL DEFAULT 0,
    payment_method      VARCHAR(50),                    -- 'card' | 'cash'
    payment_status      VARCHAR(50) DEFAULT 'pending',
    payment_intent_id   VARCHAR(255),
    status              VARCHAR(50) NOT NULL DEFAULT 'pending',
    events              JSONB NOT NULL DEFAULT '[]',
    created_at          TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP NOT NULL DEFAULT NOW()
);
```

**Foreign Keys:**
- `location_id` → `{schema}.locations(id)` CASCADE

**Unique Constraint:** `session_id`

**Indexes:**
- `{schema}_orders_location_id_idx`
- `{schema}_orders_status_idx`
- `{schema}_orders_created_at_idx`
- `{schema}_orders_customer_phone_idx`

**Created By:** IVR Service (order_tracker.py - finalize_order)

**Read By:** Conversation Engine (dashboard)

**Write By:** IVR Service

---

#### `reservations`

**Purpose:** IVR reservations

```sql
CREATE TABLE "{schema}".reservations (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    location_id             UUID NOT NULL REFERENCES "{schema}".locations(id) ON DELETE CASCADE,
    session_id              VARCHAR(255),
    reservation_number      VARCHAR(50) NOT NULL,       -- "RES-ABC-1234"
    date                    VARCHAR(10) NOT NULL,       -- YYYY-MM-DD
    time                    VARCHAR(5) NOT NULL,        -- HH:MM
    guests                  INTEGER NOT NULL,
    customer_name           VARCHAR(255),
    customer_phone          VARCHAR(20),
    customer_email          VARCHAR(255),
    special_requests        TEXT,
    status                  VARCHAR(50) NOT NULL DEFAULT 'confirmed',
    provider                VARCHAR(50) NOT NULL DEFAULT 'parcera_voice',
    created_at              TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMP NOT NULL DEFAULT NOW()
);
```

**Foreign Keys:**
- `location_id` → `{schema}.locations(id)` CASCADE

**Indexes:**
- `{schema}_reservations_location_id_idx`
- `{schema}_reservations_date_idx`
- `{schema}_reservations_status_idx`
- `{schema}_reservations_customer_phone_idx`

**Created By:** IVR Service (order_tracker.py - finalize_reservation)

**Read By:** Conversation Engine (dashboard)

**Write By:** IVR Service

---

### 4.3 Tenant Schema Summary

**Total Tables:** 13

**Purpose:** Tenant-specific data isolation

**Services:**
- ✅ Identity Service (schema creation, merchant/location management)
- ✅ Conversation Engine (agents, phone numbers, knowledge bases, call logs)
- ✅ IVR Service (orders, reservations, phone config reads)

---

## 5. Service Integration

### 5.1 Service Responsibilities

| Service | Port | Database Role | Primary Operations |
|---------|------|--------------|-------------------|
| **Identity Service** | 3001 | Schema Owner | CREATE schemas, CRUD tenants/merchants/locations/users |
| **Conversation Engine** | 3002 | Tenant Data Manager | CRUD agents/phones/knowledge, Write call_logs |
| **IVR Service** | 8000 | Read-Only (mostly) | Read config, Write orders/reservations/call_logs |

---

### 5.2 Identity Service

**Database Access:**

```typescript
// Prisma (public schema)
DATABASE_URL=postgresql://user:pass@localhost:5432/identity_db?schema=public

// Knex (tenant schemas)
DATABASE_URL=postgresql://user:pass@localhost:5432/identity_db
```

**Operations:**

| Table (Schema) | Read | Write | Notes |
|----------------|------|-------|-------|
| `public.tenants` | ✅ | ✅ | Owns tenant lifecycle |
| `public.users` | ✅ | ✅ | User management |
| `public.sessions` | ✅ | ✅ | Auth sessions |
| `public.voice_library` | ✅ | ✅ | Voice sync jobs |
| `{tenant}.merchants` | ✅ | ✅ | Via Knex (tenant schema) |
| `{tenant}.locations` | ✅ | ✅ | Via Knex (tenant schema) |
| `{tenant}.invitations` | ✅ | ✅ | Via Knex (tenant schema) |

**Key Functions:**
1. Create tenant → Create PostgreSQL schema
2. Provision merchant → Insert into `{tenant}.merchants`
3. Create location → Insert into `{tenant}.locations`

---

### 5.3 Conversation Engine Service

**Database Access:**

```typescript
// Knex (multi-tenant - switches schemas dynamically)
DATABASE_URL=postgresql://user:pass@localhost:5432/identity_db
```

**Operations:**

| Table (Schema) | Read | Write | Notes |
|----------------|------|-------|-------|
| `public.voice_library` | ✅ | ❌ | Read-only |
| `public.phone_index` | ✅ | ✅ | Manages global phone lookup |
| `{tenant}.locations` | ✅ | ✅ | Updates reservation config |
| `{tenant}.agents` | ✅ | ✅ | Full CRUD |
| `{tenant}.phone_numbers` | ✅ | ✅ | Provision + link to agent |
| `{tenant}.knowledge_bases` | ✅ | ✅ | Full CRUD |
| `{tenant}.call_logs` | ✅ | ✅ | Webhook writes |
| `{tenant}.customers` | ✅ | ✅ | Auto-created during calls |
| `{tenant}.orders` | ✅ | ❌ | Read-only (dashboard) |
| `{tenant}.reservations` | ✅ | ❌ | Read-only (dashboard) |
| `{tenant}.reservation_mirror` | ✅ | ✅ | Toast Tables mapping |
| `{tenant}.tool_registry` | ✅ | ✅ | Auto-generated tools |
| `{tenant}.agent_tools` | ✅ | ✅ | Agent-tool junction |

**Key Functions:**
1. Create agent → ElevenLabs API + DB insert
2. Provision phone → Twilio API + DB insert + ElevenLabs link
3. Sync knowledge → Update entries + ElevenLabs API
4. Webhook handler → Write call_logs + customers

---

### 5.4 IVR Service

**Database Access:**

```python
# asyncpg (multi-tenant - dynamic schema switching)
DATABASE_URL=postgresql://user:pass@localhost:5432/identity_db
```

**Operations:**

| Table (Schema) | Read | Write | Notes |
|----------------|------|-------|-------|
| `public.phone_index` | ✅ | ❌ | Fast phone → tenant lookup |
| `{tenant}.phone_numbers` | ✅ | ✅ | Read config, Write SIP IDs |
| `{tenant}.agents` | ✅ | ❌ | Read agent config |
| `{tenant}.locations` | ✅ | ❌ | Read location details |
| `{tenant}.merchants` | ✅ | ❌ | Read merchant name |
| `{tenant}.knowledge_bases` | ✅ | ❌ | Read menu data |
| `{tenant}.location_reservation_config` | ✅ | ❌ | Read policies |
| `{tenant}.orders` | ❌ | ✅ | Write-only (finalize_order) |
| `{tenant}.reservations` | ❌ | ✅ | Write-only (finalize_reservation) |
| `{tenant}.call_logs` | ❌ | ✅ | Write-only (session end) |

**Key Functions:**
1. Config resolution → Read 6 tables (phone_index + tenant schema)
2. Order finalization → Write to `orders` table
3. Reservation finalization → Write to `reservations` table
4. Call end → Upsert to `call_logs`

---

## 6. Merchant Onboarding Flow

### 6.1 API Sequence

```
┌──────────────────────────────────────────────────────────────┐
│ PHASE 1: Tenant Creation (Identity Service)                  │
└──────────────────────────────────────────────────────────────┘

Step 1: POST /tenants
  → CREATE SCHEMA "tenant_simply_south"
  → INSERT INTO public.tenants (...)
  → Call: TenantSchemaService.createTenantSchema()
  → Creates 13 tables in tenant schema

Step 2: POST /merchants?tenantId={id}
  → INSERT INTO "tenant_simply_south".merchants (...)
  → Requires activation_code (verified against public.activation_codes)

Step 3: POST /locations?tenantId={id}
  → INSERT INTO "tenant_simply_south".locations (...)
  → FK to merchants(id)

┌──────────────────────────────────────────────────────────────┐
│ PHASE 2: Voice AI Setup (Conversation Engine)                │
└──────────────────────────────────────────────────────────────┘

Step 4: POST /tenants/:tid/locations/:lid/agents
  → ElevenLabs API: Create agent
  → INSERT INTO "tenant_simply_south".agents (...)
  → Auto-generate tools → INSERT INTO tool_registry, agent_tools

Step 5: POST /tenants/:tid/locations/:lid/knowledge-base
  → INSERT INTO "tenant_simply_south".knowledge_bases (...)
  → entries = [default categories]

Step 6: PATCH /tenants/:tid/locations/:lid/knowledge-base/entries
  → UPDATE "tenant_simply_south".knowledge_bases SET entries = $1

Step 7: POST /tenants/:tid/locations/:lid/knowledge-base/sync
  → ElevenLabs API: Update agent promptStep 8: POST /tenants/:tid/locations/:lid/phone-numbers
  → Twilio API: Buy phone number
  → ElevenLabs API: Register phone
  → INSERT INTO "tenant_simply_south".phone_numbers (...)
  → INSERT INTO public.phone_index (...)

Step 9: POST /tenants/:tid/locations/:lid/phone-numbers/:pid/link
  → ElevenLabs API: Link phone to agent
  → UPDATE "tenant_simply_south".phone_numbers SET agent_id = $1

┌──────────────────────────────────────────────────────────────┐
│ COMPLETE: Calls to phone number route to Voice AI agent      │
└──────────────────────────────────────────────────────────────┘
```

---

## 7. Table Creation Timeline

### 7.1 Identity Service (on app startup)

**When:** Docker container starts

**Migrations:** Prisma (public schema)

**Tables Created:**
1. `public.users`
2. `public.tenants`
3. `public.sessions`
4. `public.devices`
5. `public.user_roles`
6. `public.permissions`
7. `public.role_permissions`
8. `public.custom_roles`
9. `public.custom_role_permissions`
10. `public.activation_codes`
11. `public.activation_batches`
12. `public.audit_events`
13. `public.verification_requests`
14. `public.recovery_requests`
15. `public.voice_library`
16. `public.api_keys`
17. `public.phone_index`

---

### 7.2 Tenant Schema Creation (POST /tenants)

**When:** Admin creates a new tenant

**Service:** Identity Service

**Method:** `TenantSchemaService.createTenantSchema(slug)`

**Tables Created (in order):**
1. `{tenant}.merchants`
2. `{tenant}.locations` (FK → merchants)
3. `{tenant}.invitations`
4. `{tenant}.agents` (FK → locations)
5. `{tenant}.tool_registry` (FK → locations)
6. `{tenant}.agent_tools` (FK → agents, tool_registry)
7. `{tenant}.knowledge_bases` (FK → locations)
8. `{tenant}.location_reservation_config` (FK → locations)
9. `{tenant}.customers` (FK → locations)
10. `{tenant}.call_logs` (FK → locations, agents, customers)
11. `{tenant}.phone_numbers` (FK → locations)
12. `{tenant}.reservation_mirror` (FK → locations)
13. `{tenant}.orders` (FK → locations)
14. `{tenant}.reservations` (FK → locations)

**Total Time:** ~200-300ms

---

### 7.3 Conversation Engine Tables

**When:** Agent/Phone provisioning

**Tables:**
- `{tenant}.agents` → Created during POST /.../agents
- `{tenant}.phone_numbers` → Created during POST /.../phone-numbers
- `{tenant}.knowledge_bases` → Created during POST /.../knowledge-base
- `{tenant}.call_logs` → Written during ElevenLabs webhooks
- `{tenant}.customers` → Auto-created during first call

---

### 7.4 IVR Service Tables

**When:** Call finalization

**Tables:**
- `{tenant}.orders` → Written during finalize_order()
- `{tenant}.reservations` → Written during finalize_reservation()
- `{tenant}.call_logs` → Upserted during session_end

---

## 8. Read/Write Operations Matrix

### 8.1 Identity Service

| Table | Read | Insert | Update | Delete | Notes |
|-------|------|--------|--------|--------|-------|
| `public.tenants` | ✅ List, Get | ✅ Create | ✅ Update status | ✅ Soft delete | Full ownership |
| `public.users` | ✅ Auth queries | ✅ Register | ✅ Profile update | ✅ Deactivate | Full ownership |
| `public.sessions` | ✅ Validate token | ✅ Login | ✅ Refresh | ✅ Logout | Full ownership |
| `public.voice_library` | ✅ List voices | ✅ Sync job | ✅ Sync job | ✅ Deactivate | Sync from ElevenLabs |
| `{tenant}.merchants` | ✅ List, Get | ✅ Create | ✅ Update | ❌ | Via Knex |
| `{tenant}.locations` | ✅ List, Get | ✅ Create | ✅ Update hours | ❌ | Via Knex |

---

### 8.2 Conversation Engine

| Table | Read | Insert | Update | Delete | Notes |
|-------|------|--------|--------|--------|-------|
| `public.voice_library` | ✅ List voices | ❌ | ❌ | ❌ | Read-only |
| `public.phone_index` | ✅ Lookup tenant | ✅ On provision | ✅ On update | ✅ On release | Global index |
| `{tenant}.agents` | ✅ List, Get | ✅ Create | ✅ Update prompt | ✅ Deactivate | Full CRUD |
| `{tenant}.phone_numbers` | ✅ List, Get | ✅ Provision | ✅ Link agent | ✅ Release | Full CRUD |
| `{tenant}.knowledge_bases` | ✅ Get | ✅ Create | ✅ Update entries | ❌ | Full CRUD |
| `{tenant}.call_logs` | ✅ Dashboard | ✅ Webhook | ✅ Webhook | ❌ | Append-only |
| `{tenant}.customers` | ✅ Lookup | ✅ First call | ✅ Update count | ❌ | Auto-managed |
| `{tenant}.orders` | ✅ Dashboard | ❌ | ❌ | ❌ | Read-only |
| `{tenant}.reservations` | ✅ Dashboard | ❌ | ❌ | ❌ | Read-only |

---

### 8.3 IVR Service

| Table | Read | Insert | Update | Delete | Notes |
|-------|------|--------|--------|--------|-------|
| `public.phone_index` | ✅ Fast lookup | ❌ | ❌ | ❌ | Read-only |
| `{tenant}.phone_numbers` | ✅ SIP config | ❌ | ✅ SIP IDs | ❌ | Onboarding only |
| `{tenant}.agents` | ✅ Config | ❌ | ❌ | ❌ | Read-only |
| `{tenant}.locations` | ✅ Config | ❌ | ❌ | ❌ | Read-only |
| `{tenant}.knowledge_bases` | ✅ Menu data | ❌ | ❌ | ❌ | Read-only |
| `{tenant}.orders` | ❌ | ✅ Finalize | ❌ | ❌ | Write-only |
| `{tenant}.reservations` | ❌ | ✅ Finalize | ❌ | ❌ | Write-only |
| `{tenant}.call_logs` | ❌ | ✅ Session end | ✅ Session end | ❌ | Upsert (conversation_id) |

---

## 9. Dependencies & Foreign Keys

### 9.1 Public Schema Dependencies

```
users (root)
  ├── tenants (created_by FK)
  ├── sessions (user_id FK)
  ├── devices (user_id FK)
  ├── user_roles (user_id FK)
  ├── activation_batches (created_by FK)
  └── audit_events (user_id FK)

tenants (root)
  ├── custom_roles (tenant_id FK)
  ├── activation_codes (tenant_id FK)
  ├── devices (tenant_id FK)
  └── api_keys (tenant_id FK)

permissions (root)
  ├── role_permissions (permission_id FK)
  └── custom_role_permissions (permission_id FK)
```

---

### 9.2 Tenant Schema Dependencies

```
merchants (root within tenant schema)
  └── locations (merchant_id FK)
        ├── agents (location_id FK)
        │     └── agent_tools (agent_id FK)
        ├── tool_registry (location_id FK)
        │     └── agent_tools (tool_registry_id FK)
        ├── knowledge_bases (location_id FK)
        ├── location_reservation_config (location_id FK)
        ├── customers (location_id FK)
        ├── call_logs (location_id FK, agent_id FK, customer_id FK)
        ├── phone_numbers (location_id FK)
        ├── reservation_mirror (location_id FK)
        ├── orders (location_id FK)
        └── reservations (location_id FK)
```

**Critical Path (must exist):**
1. Merchant → Location → Agent → Knowledge Base → Phone Number → Calls

---

## 10. Migration Strategy

### 10.1 Public Schema Migrations

**Tool:** Prisma

**Location:** `apps/identity-service/prisma/migrations/`

**Workflow:**
```bash
# Generate migration
npm run prisma:migrate:dev

# Apply to production
npm run prisma:migrate:deploy
```

**Rollback:** Prisma does not support automatic rollback. Manual SQL required.

---

### 10.2 Tenant Schema Migrations

**Tool:** Knex (programmatic)

**Location:** `apps/identity-service/src/tenant/tenant-schema.service.ts`

**Workflow:**
```typescript
// On app startup: Migrate all existing tenant schemas
@OnModuleInit()
async onModuleInit() {
  await this.migrateAllTenantSchemas();
}

// Add new column to all tenant schemas
async migrateAllTenantSchemas() {
  const schemas = await this.listTenantSchemas();
  for (const schema of schemas) {
    await this.migrateLocationsTable(schema);
    // Add more migrations as needed
  }
}
```

**Idempotent Migrations:**
```typescript
const hasColumn = await this.knex.schema
  .withSchema(schemaName)
  .hasColumn('locations', 'new_column');

if (!hasColumn) {
  await this.knex.schema.withSchema(schemaName)
    .alterTable('locations', (table) => {
      table.string('new_column', 255);
    });
}
```

---

### 10.3 Version Control

**Schema Version Tracking:**

```sql
CREATE TABLE "public"."schema_versions" (
    schema_name         VARCHAR(100) PRIMARY KEY,
    version             INTEGER NOT NULL,
    last_migrated_at    TIMESTAMP NOT NULL DEFAULT NOW()
);
```

**Future Enhancement:** Track tenant schema versions for targeted migrations.

---

## 11. ElevenLabs Integration

### 11.1 Tables Involved

| Table | ElevenLabs Field | Purpose |
|-------|------------------|---------|
| `{tenant}.agents` | `agent_id_external` | ElevenLabs agent ID |
| `{tenant}.phone_numbers` | `elevenlabs_phone_number_id` | ElevenLabs phone ID |
| `{tenant}.knowledge_bases` | `external_id` | ElevenLabs knowledge base ID |
| `{tenant}.tool_registry` | `external_id` | ElevenLabs tool ID |
| `{tenant}.call_logs` | `elevenlabs_user_id` | ElevenLabs user ID (from webhook) |

---

### 11.2 Onboarding Creates in ElevenLabs

**Step 1: Create Agent (POST /.../agents)**

```
API Call: POST https://api.elevenlabs.io/v1/convai/agents
Response: { agent_id: "agent_1501..." }
DB Write: INSERT INTO "{tenant}".agents (agent_id_external = "agent_1501...")
```

**Step 2: Sync Knowledge (POST /.../knowledge-base/sync)**

```
API Call: PATCH https://api.elevenlabs.io/v1/convai/agents/{agent_id}
Body: { prompt: { prompt: "system prompt" }, knowledge_base: { ... } }
Response: 200 OK
DB Write: UPDATE "{tenant}".knowledge_bases SET sync_status = 'synced'
```

**Step 3: Register Phone (POST /.../phone-numbers)**

```
API Call: POST https://api.elevenlabs.io/v1/convai/phone_numbers
Body: { phone_number: "+14432429429", region: "US" }
Response: { phone_number_id: "ph_abc123..." }
DB Write: INSERT INTO "{tenant}".phone_numbers (elevenlabs_phone_number_id = "ph_abc123...")
```

**Step 4: Link Phone to Agent (POST /.../phone-numbers/:id/link)**

```
API Call: PATCH https://api.elevenlabs.io/v1/convai/agents/{agent_id}
Body: { conversation_config: { agent: { phone_number_id: "ph_abc123..." } } }
Response: 200 OK
DB Write: UPDATE "{tenant}".phone_numbers SET agent_id = "{agent_id_external}"
```

---

## 12. Summary

### Total Tables: 31

**Public Schema:** 18 tables (shared)  
**Tenant Schema (per tenant):** 13 tables (isolated)

### Service Ownership

| Service | Primary Responsibility |
|---------|----------------------|
| Identity Service | Public schema + Tenant schema creation |
| Conversation Engine | Tenant data CRUD + ElevenLabs sync |
| IVR Service | Runtime reads + Order/Reservation writes |

### Critical Onboarding Path

```
1. POST /tenants (Identity Service)
   → CREATE SCHEMA tenant_{slug}
   → Create 13 tables

2. POST /merchants (Identity Service)
   → INSERT INTO {tenant}.merchants

3. POST /locations (Identity Service)
   → INSERT INTO {tenant}.locations

4. POST /.../agents (Conversation Engine)
   → ElevenLabs API
   → INSERT INTO {tenant}.agents

5. POST /.../knowledge-base (Conversation Engine)
   → INSERT INTO {tenant}.knowledge_bases

6. POST /.../phone-numbers (Conversation Engine)
   → Twilio API + ElevenLabs API
   → INSERT INTO {tenant}.phone_numbers
   → INSERT INTO public.phone_index

7. POST /.../phone-numbers/:id/link (Conversation Engine)
   → ElevenLabs API
   → UPDATE {tenant}.phone_numbers

RESULT: Phone calls route to Voice AI agent ✅
```

---

*End of Documentation*
