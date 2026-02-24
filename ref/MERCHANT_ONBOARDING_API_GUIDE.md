# Merchant Onboarding API Guide - Parcera Voice AI Integration

> **Purpose:** Document all APIs triggered during merchant onboarding and how they connect to enable Voice AI functionality
> **Last Updated:** February 23, 2026

---

## Table of Contents

1. [Overview](#1-overview)
2. [API Reference by Service](#2-api-reference-by-service)
3. [Architecture Diagram](#3-architecture-diagram)
4. [Complete Onboarding Flow](#4-complete-onboarding-flow)
5. [Database Structure](#5-database-structure)

---

## 1. Overview

When a merchant is onboarded to Parcera, multiple APIs across **Identity Service** and **Conversation Engine** work together to set up:
- Multi-tenant data isolation (PostgreSQL schemas)
- Location configuration
- Voice AI agent creation
- Phone number provisioning & PSTN routing
- Knowledge base for AI context

**Services Involved:**
| Service | Port | Purpose |
|---------|------|---------|
| Identity Service | 3001 | Tenant/Merchant/Location management, Authentication |
| Conversation Engine | 3002 | Voice AI agents, Phone numbers, Knowledge base |
| IVR Service | 8000 | LiveKit SIP trunks, PSTN routing (optional) |

---

## 2. API Reference by Service

### Identity Service APIs (`http://localhost:3001`)

| # | Method | Endpoint | Purpose |
|---|--------|----------|---------|
| 1 | `POST` | `/tenants` | Create tenant organization (creates PostgreSQL schema for data isolation) |
| 2 | `GET` | `/tenants/:id` | Get tenant details including `schemaName` |
| 3 | `POST` | `/merchants` | Create merchant within tenant (requires activation code) |
| 4 | `GET` | `/merchants` | List merchants (requires `tenantId` query param) |
| 5 | `POST` | `/locations?tenantId={id}` | Create location for merchant |
| 6 | `GET` | `/locations?merchantId={id}&tenantId={id}` | List locations for a merchant |
| 7 | `GET` | `/locations/:locationId?tenantId={id}` | Get single location details |

#### API Details

**POST /tenants** - Create Tenant
```json
// Request
{
  "name": "Simply South Restaurant Group",
  "slug": "simply-south",
  "type": "enterprise",
  "contactEmail": "contact@simplysouth.com"
}

// Response (201)
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "name": "Simply South Restaurant Group",
  "slug": "simply-south",
  "schemaName": "tenant_simply_south",  // ← PostgreSQL schema created
  "status": "active"
}
```

**POST /merchants** - Create Merchant
```json
// Request (requires activationCode)
{
  "name": "Simply South - Downtown",
  "businessType": "restaurant",
  "tenantId": "550e8400-e29b-41d4-a716-446655440000",
  "activationCode": "SIMPLY-XXXXX"
}

// Response (201)
{
  "id": "660e8400-e29b-41d4-a716-446655440001",
  "name": "Simply South - Downtown",
  "tenantId": "550e8400-e29b-41d4-a716-446655440000"
}
```

**POST /locations** - Create Location
```json
// Request (query: ?tenantId=xxx)
{
  "merchantId": "660e8400-e29b-41d4-a716-446655440001",
  "name": "Downtown Branch",
  "address": "123 Main St, Atlanta, GA",
  "timezone": "America/New_York",
  "reservationProvider": "toast_tables",
  "businessDomain": "restaurant"
}

// Response (201)
{
  "id": "770e8400-e29b-41d4-a716-446655440002",
  "merchantId": "660e8400-e29b-41d4-a716-446655440001",
  "name": "Downtown Branch",
  "schemaName": "tenant_simply_south"
}
```

---

### Conversation Engine APIs (`http://localhost:3002`)

All endpoints are scoped by tenant and location:
```
/tenants/:tenantId/locations/:locationId/...
```

| # | Method | Endpoint | Purpose |
|---|--------|----------|---------|
| 1 | `POST` | `/.../agents` | Create Voice AI agent (ElevenLabs/Parcera Voice) |
| 2 | `GET` | `/.../agents` | List agents for location |
| 3 | `PATCH` | `/.../agents/:agentId` | Update agent settings |
| 4 | `POST` | `/.../agents/:agentId/system-prompt` | Generate & apply system prompt |
| 5 | `POST` | `/.../knowledge-base` | Create knowledge base for AI context |
| 6 | `PATCH` | `/.../knowledge-base/entries` | Update knowledge entries (hours, menu, etc.) |
| 7 | `POST` | `/.../knowledge-base/sync` | Sync knowledge base to ElevenLabs |
| 8 | `GET` | `/.../phone-numbers/pool` | Get available phone numbers from pool |
| 9 | `POST` | `/.../phone-numbers` | Provision PSTN phone number (Twilio) |
| 10 | `POST` | `/.../phone-numbers/:id/link` | Link phone to agent for call routing |
| 11 | `DELETE` | `/.../phone-numbers/:id/link` | Unlink phone from agent |
| 12 | `GET` | `/.../orders` | List IVR orders for location |
| 13 | `GET` | `/.../ivr-reservations` | List IVR reservations for location |

#### API Details

**POST /.../agents** - Create Voice AI Agent
```json
// Request
{
  "provider": "elevenlabs",  // or "parcera_voice"
  "agentName": "Simply South AI Assistant",
  "firstMessage": "Hi, thank you for calling Simply South! How can I help you today?",
  "systemPrompt": "You are a helpful restaurant assistant...",
  "voiceId": "21m00Tcm4TlvDq8ikWAM",
  "language": "en",
  "llmModel": "gpt-4o-mini",
  "temperature": 0.7,
  "fallbackPhone": "+14045551234"
}

// Response (201)
{
  "success": true,
  "data": {
    "id": "880e8400-e29b-41d4-a716-446655440003",
    "locationId": "770e8400-e29b-41d4-a716-446655440002",
    "provider": "elevenlabs",
    "agentIdExternal": "agent_1501kfg54wjsfk5amrc0gahyt5y6",  // ← ElevenLabs agent ID
    "isActive": true
  }
}
```

**POST /.../phone-numbers** - Provision Phone Number
```json
// Request
{
  "provisionMode": "random",  // 'random' | 'user-selected' | 'from-pool'
  "label": "Main Restaurant Line",
  "voiceAiProvider": "elevenlabs"
}

// Response (201)
{
  "success": true,
  "data": {
    "id": "990e8400-e29b-41d4-a716-446655440004",
    "phoneNumber": "+18005551234",
    "label": "Main Restaurant Line",
    "provider": "twilio",
    "elevenLabsPhoneNumberId": "ph_abc123xyz",
    "status": "in-use"
  },
  "isNew": true
}
```

**POST /.../phone-numbers/:id/link** - Link Phone to Agent
```json
// Request
{
  "agentIdExternal": "agent_1501kfg54wjsfk5amrc0gahyt5y6"
}

// Response (200)
{
  "success": true,
  "data": {
    "id": "990e8400-e29b-41d4-a716-446655440004",
    "phoneNumber": "+18005551234",
    "agentId": "agent_1501kfg54wjsfk5amrc0gahyt5y6"  // ← Now linked
  }
}
```

**POST /.../knowledge-base** - Create Knowledge Base
```json
// Response (201) - Auto-populated with category templates
{
  "success": true,
  "data": {
    "id": "aaa...",
    "locationId": "770e...",
    "name": "Simply South - Downtown Knowledge Base",
    "entries": [
      { "category": "business_hours", "label": "Operating Hours", "content": null, "isEnabled": true },
      { "category": "menu_info", "label": "Menu Information", "content": null, "isEnabled": true },
      { "category": "location_address", "label": "Address & Directions", "content": null, "isEnabled": true }
    ],
    "syncStatus": "pending"
  }
}
```

---

### IVR Service APIs (`http://localhost:8000`) - Optional

For advanced PSTN routing with LiveKit (Parcera Voice provider):

| # | Method | Endpoint | Purpose |
|---|--------|----------|---------|
| 1 | `GET` | `/health` | Health check |
| 2 | `POST` | `/token` | Generate LiveKit room token for WebRTC calls |
| 3 | `POST` | `/outbound-call` | Trigger outbound PSTN call |
| 4 | `POST` | `/onboard-phone` | Provision LiveKit + Twilio SIP trunks |
| 5 | `POST` | `/cleanup-phone` | Teardown SIP trunks |

---

## 3. Architecture Diagram

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              ADMIN / DASHBOARD                               │
│                            (React Merchant Console)                          │
└─────────────────────────────────────────────────────────────────────────────┘
                                       │
                                       ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                              API GATEWAY / NGINX                             │
│  /api → identity-service    /conversation-api → conversation-engine-service │
└─────────────────────────────────────────────────────────────────────────────┘
                          │                          │
              ┌───────────┴───────────┐  ┌──────────┴──────────────────┐
              ▼                       ▼  ▼                              ▼
┌─────────────────────────┐  ┌───────────────────────────────────────────────┐
│   IDENTITY SERVICE      │  │           CONVERSATION ENGINE                  │
│   (NestJS :3001)        │  │           (NestJS :3002)                       │
├─────────────────────────┤  ├─────────────────────────────────────────────────┤
│ • Tenant CRUD           │  │ • Agent CRUD (ElevenLabs sync)                  │
│ • Merchant CRUD         │  │ • Phone Number Provisioning                     │
│ • Location CRUD         │  │ • Knowledge Base Management                     │
│ • User Auth (Keycloak)  │  │ • Orders & Reservations (Dashboard)             │
│ • Schema Creation       │  │ • Webhook Handlers (ElevenLabs)                 │
└───────────┬─────────────┘  └─────────────────┬─────────────────────────────┘
            │                                   │
            └─────────────┬─────────────────────┘
                          ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                        POSTGRESQL DATABASE                                   │
│─────────────────────────────────────────────────────────────────────────────│
│  public schema:                                                              │
│    • users, sessions, devices (auth)                                         │
│    • tenants, permissions, roles                                             │
│    • activation_codes, audit_events                                          │
│    • voice_library (shared voices)                                           │
│─────────────────────────────────────────────────────────────────────────────│
│  tenant_simply_south schema (per-tenant isolation):                          │
│    • merchants            - Business entities                                │
│    • locations            - Physical locations                               │
│    • agents               - Voice AI agents                                  │
│    • phone_numbers        - Twilio PSTN numbers                              │
│    • knowledge_bases      - AI context data                                  │
│    • call_logs            - Call history                                     │
│    • customers            - Caller identity                                  │
│    • orders               - IVR food orders                                  │
│    • reservations         - IVR reservations                                 │
│    • tool_registry        - Custom agent tools                               │
│    • location_reservation_config - Provider config                           │
└─────────────────────────────────────────────────────────────────────────────┘
                                       │
              ┌────────────────────────┼────────────────────────┐
              │                        │                        │
              ▼                        ▼                        ▼
┌─────────────────────┐  ┌─────────────────────┐  ┌─────────────────────┐
│   TWILIO API        │  │   ELEVENLABS API    │  │  TOAST TABLES API   │
│   (Phone Service)   │  │   (Conversational   │  │  (Reservations)     │
│                     │  │    AI + PSTN)       │  │                     │
│ • Buy phone numbers │  │ • Create agents     │  │ • Availability      │
│ • SIP trunks        │  │ • Register phones   │  │ • Create booking    │
│ • SMS               │  │ • Webhooks          │  │ • Modify/Cancel     │
└─────────────────────┘  └─────────────────────┘  └─────────────────────┘
```

---

## 4. Complete Onboarding Flow

### Step-by-Step API Sequence

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    MERCHANT ONBOARDING SEQUENCE                              │
└─────────────────────────────────────────────────────────────────────────────┘

 ┌──────────────────────────────────────────────────────────────────────────┐
 │ PHASE 1: Organization Setup (Identity Service)                           │
 └──────────────────────────────────────────────────────────────────────────┘
 
 Step 1: Create Tenant
 ┌─────────┐                    ┌──────────────────┐                ┌────────┐
 │ Admin   │─── POST /tenants ──→│ Identity Service │── CREATE ────→│   DB   │
 │         │                    │                  │  tenant +      │        │
 │         │←── { schemaName } ─│                  │  schema        │        │
 └─────────┘                    └──────────────────┘                └────────┘
                                       │
                                       ▼
                        ┌──────────────────────────────┐
                        │ PostgreSQL: CREATE SCHEMA    │
                        │ "tenant_simply_south"        │
                        │                              │
                        │ Tables created:              │
                        │ • merchants, locations       │
                        │ • agents, phone_numbers      │
                        │ • knowledge_bases, call_logs │
                        │ • orders, reservations       │
                        └──────────────────────────────┘

 Step 2: Create Merchant
 ┌─────────┐                    ┌──────────────────┐
 │ Admin   │─── POST /merchants ─→│ Identity Service │
 │         │    + activationCode │                  │
 │         │←── { merchantId } ──│                  │
 └─────────┘                    └──────────────────┘

 Step 3: Create Location
 ┌─────────┐                    ┌──────────────────┐
 │ Admin   │─ POST /locations ──→│ Identity Service │
 │         │  ?tenantId=xxx      │                  │
 │         │←── { locationId } ──│                  │
 └─────────┘                    └──────────────────┘

 ┌──────────────────────────────────────────────────────────────────────────┐
 │ PHASE 2: Voice AI Setup (Conversation Engine)                            │
 └──────────────────────────────────────────────────────────────────────────┘

 Step 4: Create Voice Agent
 ┌─────────┐                    ┌──────────────────┐       ┌─────────────┐
 │ Admin   │─ POST /.../agents ─→│ Conversation Eng │──────→│ ElevenLabs  │
 │         │                    │                  │create │             │
 │         │←─ { agentId,       │                  │agent  │             │
 │         │   agentIdExternal }│                  │←──────│             │
 └─────────┘                    └──────────────────┘       └─────────────┘

 Step 5: Create Knowledge Base
 ┌─────────┐                    ┌──────────────────┐
 │ Admin   │─ POST /.../        │ Conversation Eng │
 │         │  knowledge-base    →│                  │
 │         │←─ { id, entries } ─│                  │
 └─────────┘                    └──────────────────┘

 Step 6: Update Knowledge Entries (Hours, Menu, etc.)
 ┌─────────┐                    ┌──────────────────┐
 │ Admin   │─ PATCH /.../       │ Conversation Eng │
 │         │  knowledge-base/   →│                  │
 │         │  entries            │                  │
 └─────────┘                    └──────────────────┘

 Step 7: Sync Knowledge to ElevenLabs
 ┌─────────┐                    ┌──────────────────┐       ┌─────────────┐
 │ Admin   │─ POST /.../        │ Conversation Eng │──────→│ ElevenLabs  │
 │         │  knowledge-base/   →│                  │update │             │
 │         │  sync               │                  │agent  │             │
 └─────────┘                    └──────────────────┘prompt └─────────────┘

 ┌──────────────────────────────────────────────────────────────────────────┐
 │ PHASE 3: Phone Number Setup (Conversation Engine → Twilio → ElevenLabs)  │
 └──────────────────────────────────────────────────────────────────────────┘

 Step 8: Provision Phone Number
 ┌─────────┐                    ┌──────────────────┐       ┌─────────────┐
 │ Admin   │─ POST /.../        │ Conversation Eng │──────→│   Twilio    │
 │         │  phone-numbers     →│                  │buy #  │             │
 │         │                    │                  │←──────│             │
 │         │                    │                  │──────→│ ElevenLabs  │
 │         │←─ { phoneNumber,   │                  │register│            │
 │         │   elevenLabsId }   │                  │←──────│             │
 └─────────┘                    └──────────────────┘       └─────────────┘

 Step 9: Link Phone to Agent
 ┌─────────┐                    ┌──────────────────┐       ┌─────────────┐
 │ Admin   │─ POST /.../        │ Conversation Eng │──────→│ ElevenLabs  │
 │         │  phone-numbers/    →│                  │assign │             │
 │         │  {id}/link          │                  │phone  │             │
 │         │                    │                  │to     │             │
 │         │←─ { linked }       │                  │agent  │             │
 └─────────┘                    └──────────────────┘       └─────────────┘

 ┌──────────────────────────────────────────────────────────────────────────┐
 │ COMPLETE! Phone calls to provisioned number now route to Voice AI Agent  │
 └──────────────────────────────────────────────────────────────────────────┘

 Call Flow:
 ┌─────────┐       ┌─────────┐       ┌─────────────┐       ┌──────────────┐
 │ Caller  │──────→│  PSTN   │──────→│ ElevenLabs  │←─────→│ Conversation │
 │ Phone   │ dial  │ (Twilio)│ route │  Agent      │webhook│   Engine     │
 │         │       │         │       │             │calls  │ (tool exec)  │
 └─────────┘       └─────────┘       └─────────────┘       └──────────────┘
```

---

## 5. Database Structure

### Tables Created During Onboarding

**Public Schema (shared across all tenants):**
| Table | Purpose | Created By |
|-------|---------|------------|
| `tenants` | Tenant organizations | POST /tenants |
| `users` | User accounts | Auth flow |
| `voice_library` | Shared voice configurations | Admin setup |

**Tenant Schema (e.g., `tenant_simply_south`):**
| Table | Purpose | Created By |
|-------|---------|------------|
| `merchants` | Business entities | POST /merchants |
| `locations` | Physical locations | POST /locations |
| `agents` | Voice AI agents | POST /.../agents |
| `phone_numbers` | Twilio PSTN numbers | POST /.../phone-numbers |
| `knowledge_bases` | AI context/prompt data | POST /.../knowledge-base |
| `call_logs` | Call history | Auto (during calls) |
| `customers` | Caller identity | Auto (during calls) |
| `orders` | IVR food orders | Auto (during calls) |
| `reservations` | IVR reservations | Auto (during calls) |
| `tool_registry` | Custom agent tools | POST /.../agents (auto) |
| `location_reservation_config` | Reservation provider config | Admin setup |

### Key Relationships

```sql
-- Within tenant schema (e.g., tenant_simply_south):

merchants (1) ─────< locations (*)
                         │
                         ├───< agents (1 per location)
                         │        │
                         │        └───< agent_tools ───< tool_registry
                         │
                         ├───< phone_numbers (linked to agents)
                         │
                         ├───< knowledge_bases (1 per location)
                         │
                         ├───< call_logs ───> customers
                         │
                         ├───< orders
                         │
                         └───< reservations
```

---

## Quick Start Checklist

```
□ 1. POST /tenants         → Get tenantId + schemaName
□ 2. POST /merchants       → Get merchantId (needs activation code)
□ 3. POST /locations       → Get locationId
□ 4. POST /.../agents      → Get agentId + agentIdExternal
□ 5. POST /.../knowledge-base → Initialize knowledge
□ 6. PATCH /.../knowledge-base/entries → Add business info
□ 7. POST /.../knowledge-base/sync → Sync to ElevenLabs
□ 8. POST /.../phone-numbers → Get phone number
□ 9. POST /.../phone-numbers/:id/link → Connect phone to agent
□ 10. TEST: Call the phone number!
```

---

## Environment Variables Required

**Identity Service:**
```env
DATABASE_URL=postgresql://user:pass@localhost:5432/parcera
KEYCLOAK_URL=http://localhost:8080
```

**Conversation Engine:**
```env
DATABASE_URL=postgresql://user:pass@localhost:5432/parcera
ELEVENLABS_API_KEY=sk_...
TWILIO_ACCOUNT_SID=AC...
TWILIO_AUTH_TOKEN=...
TWILIO_PHONE_NUMBER=+1...  # Default for provisioning
```

**IVR Service (optional):**
```env
LIVEKIT_URL=wss://xxx.livekit.cloud
LIVEKIT_API_KEY=...
LIVEKIT_API_SECRET=...
LIVEKIT_SIP_URI=xxx.sip.livekit.cloud
```

---

## Related Documentation

- [PSTN_API_INTEGRATION_GUIDE.md](PSTN_API_INTEGRATION_GUIDE.md) - Detailed phone number API docs
- [IVR_INTEGRATION_PLAN.md](IVR_INTEGRATION_PLAN.md) - Parcera Voice vs ElevenLabs comparison
- [Identity Service README](../apps/identity-service/Readme.md) - Auth & RBAC details
- [Conversation Engine Postman](../apps/conversation-engine-service/postman/) - API collection
