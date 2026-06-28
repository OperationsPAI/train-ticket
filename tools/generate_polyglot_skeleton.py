#!/usr/bin/env python3
from __future__ import annotations

import json
import re
from pathlib import Path
from xml.sax.saxutils import escape as xml_escape


ROOT = Path(__file__).resolve().parents[1]

SPRING_BOOT_VERSION = "4.1.0"
JAVA_VERSION = "25"
GO_DIRECTIVE = "1.26"
GIN_VERSION = "v1.12.0"
FASTAPI_VERSION = "0.138.1"
PYTEST_VERSION = "9.1.1"
UV_BUILD_VERSION = "0.11.25"
AXUM_VERSION = "0.8.9"
RUST_EDITION = "2024"
FASTIFY_VERSION = "5.9.0"
TYPESCRIPT_VERSION = "6.0.3"
TYPES_NODE_VERSION = "26.0.1"


PLATFORM_MODULES = [
    {
        "id": "shared-kernel",
        "path": "platform/shared-kernel-rust",
        "domain": "Shared Kernel / Platform",
        "language": "rust",
        "runtime": "rust",
        "phase": "phase-1-foundation",
        "status": "skeleton",
        "priority": "P0",
        "workPackages": ["WP-01"],
        "docs": [
            "docs/03-ddd-final/implementation-roadmap.md",
            "docs/03-ddd-final/phase-1-contract.md",
        ],
        "owns": [
            "reference implementation for IDs, value objects, and event envelope",
            "language-neutral contract source before code generation exists",
        ],
        "rationale": "Rust keeps shared invariant examples strict while the normative contract remains language-neutral.",
    }
]


SERVICES = [
    {
        "id": "place-network",
        "domain": "Place & Network",
        "language": "golang",
        "runtime": "go",
        "phase": "phase-1-core",
        "status": "skeleton",
        "priority": "P0",
        "workPackages": ["WP-02"],
        "docs": ["docs/02-domains/place-network.md"],
        "owns": ["Place", "TransportNode", "ProviderPlaceMapping"],
        "rationale": "Go fits read-heavy master-data APIs and simple operational lookup paths.",
    },
    {
        "id": "service-plan",
        "domain": "Service Plan",
        "language": "golang",
        "runtime": "go",
        "phase": "phase-1-core",
        "status": "skeleton",
        "priority": "P0",
        "workPackages": ["WP-03"],
        "docs": ["docs/02-domains/service-plan.md"],
        "owns": ["Route", "ServicePlan", "Calendar", "Timetable", "PlanVersion"],
        "rationale": "Go keeps schedule query services small, fast, and easy to operate.",
    },
    {
        "id": "capacity-availability",
        "domain": "Capacity & Availability",
        "language": "rust",
        "runtime": "rust",
        "phase": "phase-1-core",
        "status": "skeleton",
        "priority": "P0",
        "workPackages": ["WP-05"],
        "docs": ["docs/02-domains/capacity-availability.md"],
        "owns": ["InventoryPool", "CapacityHold", "Quota"],
        "rationale": "Rust is appropriate for interval overlap and seat-hold invariants that must fail closed.",
    },
    {
        "id": "fare-pricing",
        "domain": "Fare & Pricing",
        "language": "python",
        "runtime": "python",
        "phase": "phase-1-core",
        "status": "skeleton",
        "priority": "P0",
        "workPackages": ["WP-04"],
        "docs": ["docs/02-domains/fare-pricing.md"],
        "owns": ["FareRuleSet", "FareQuote", "RefundFee", "ChangeFee", "RuleSnapshot"],
        "rationale": "Python keeps fare rules, explanation tooling, and later experimentation lightweight.",
    },
    {
        "id": "trip-planning",
        "domain": "Trip Planning",
        "language": "python",
        "runtime": "python",
        "phase": "phase-1-core",
        "status": "skeleton",
        "priority": "P0",
        "workPackages": ["WP-06"],
        "docs": ["docs/02-domains/trip-planning.md"],
        "owns": ["TripIntent", "Itinerary", "SearchResult", "PriceHint"],
        "rationale": "Python is a better fit for search heuristics, ranking, and explanation logic.",
    },
    {
        "id": "offer-management",
        "domain": "Offer Management",
        "language": "typescript",
        "runtime": "node",
        "phase": "phase-1-core",
        "status": "skeleton",
        "priority": "P0",
        "workPackages": ["WP-07"],
        "docs": ["docs/02-domains/offer-management.md"],
        "owns": ["Offer", "OfferItem", "PriceSnapshot", "RiskDisclosure", "ChangeOffer"],
        "rationale": "TypeScript fits user-facing offer API composition, TTL policy, and disclosure payloads.",
    },
    {
        "id": "journey-order",
        "domain": "Journey Order",
        "language": "java",
        "runtime": "jvm",
        "phase": "phase-1-core",
        "status": "skeleton",
        "priority": "P0",
        "workPackages": ["WP-08"],
        "docs": ["docs/02-domains/journey-order.md"],
        "owns": ["JourneyOrder", "OrderItem", "MonetarySummary", "OrderTimeline"],
        "rationale": "Java is conservative for central transactional aggregates and long-lived domain models.",
    },
    {
        "id": "booking-orchestration",
        "domain": "Booking Orchestration",
        "language": "java",
        "runtime": "jvm",
        "phase": "phase-1-core",
        "status": "skeleton",
        "priority": "P0",
        "workPackages": ["WP-09"],
        "docs": ["docs/02-domains/booking-orchestration.md"],
        "owns": ["BookingSaga", "SegmentBooking", "ProviderReservation mapping"],
        "rationale": "Java gives the saga layer explicit state transitions and stable integration testing options.",
    },
    {
        "id": "payment",
        "domain": "Payment",
        "language": "java",
        "runtime": "jvm",
        "phase": "phase-1-core",
        "status": "skeleton",
        "priority": "P0",
        "workPackages": ["WP-10"],
        "docs": ["docs/02-domains/payment.md"],
        "owns": ["PaymentIntent", "Refund", "CallbackRecord", "IdempotencyKey"],
        "rationale": "Java is a strong default for money state machines, idempotency, and audit-heavy flows.",
    },
    {
        "id": "provider-integration",
        "domain": "Provider Integration",
        "language": "golang",
        "runtime": "go",
        "phase": "phase-1-support",
        "status": "skeleton",
        "priority": "P0",
        "workPackages": ["WP-11"],
        "docs": ["docs/02-domains/provider-integration.md"],
        "owns": ["adapter protocol", "signature verification", "raw archive", "external status mapping"],
        "rationale": "Go fits protocol adapters, webhooks, retry loops, and small deployable edge services.",
    },
    {
        "id": "entitlement-ticketing",
        "domain": "Entitlement & Ticketing",
        "language": "rust",
        "runtime": "rust",
        "phase": "phase-1-core",
        "status": "skeleton",
        "priority": "P0",
        "workPackages": ["WP-12"],
        "docs": ["docs/02-domains/entitlement-ticketing.md"],
        "owns": ["Entitlement", "Credential", "Issue", "Void", "Suspend", "Boarded consumption"],
        "rationale": "Rust is appropriate for ticket entitlement lifecycle invariants and credential safety.",
    },
    {
        "id": "fulfillment",
        "domain": "Fulfillment",
        "language": "golang",
        "runtime": "go",
        "phase": "phase-1-limited",
        "status": "skeleton",
        "priority": "P1",
        "workPackages": ["WP-13"],
        "docs": ["docs/02-domains/fulfillment.md"],
        "owns": ["FulfillmentRecord", "BoardingVerified", "NoShow", "EvidenceDispute"],
        "rationale": "Go keeps event ingestion and station-side fact normalization operationally simple.",
    },
    {
        "id": "post-sales",
        "domain": "Post Sales",
        "language": "java",
        "runtime": "jvm",
        "phase": "phase-1-core",
        "status": "skeleton",
        "priority": "P0",
        "workPackages": ["WP-14"],
        "docs": ["docs/02-domains/post-sales.md"],
        "owns": ["PostSalesCase", "RefundDecision", "ChangeExecutionPlan"],
        "rationale": "Java keeps refund/change case workflows explicit and compatible with transaction review tooling.",
    },
    {
        "id": "notification",
        "domain": "Notification",
        "language": "typescript",
        "runtime": "node",
        "phase": "phase-1-support",
        "status": "skeleton",
        "priority": "P1",
        "workPackages": ["WP-15"],
        "docs": ["docs/02-domains/notification.md"],
        "owns": ["NotificationTask", "Template", "RecipientPolicy", "DeliveryReceipt"],
        "rationale": "TypeScript fits template payloads, channel adapters, and product-facing notification policies.",
    },
    {
        "id": "traveler-profile",
        "domain": "Traveler Profile",
        "language": "java",
        "runtime": "jvm",
        "phase": "phase-1-support",
        "status": "skeleton",
        "priority": "P0",
        "workPackages": ["WP-16"],
        "docs": ["docs/02-domains/traveler-profile.md"],
        "owns": ["TravelerProfile", "Document", "EligibilitySummary", "PreferenceSnapshot"],
        "rationale": "Java keeps PII-heavy profile aggregates and validation policies explicit.",
    },
    {
        "id": "risk-compliance",
        "domain": "Risk & Compliance",
        "language": "python",
        "runtime": "python",
        "phase": "phase-1-support",
        "status": "skeleton",
        "priority": "P1",
        "workPackages": ["WP-17"],
        "docs": ["docs/02-domains/risk-compliance.md"],
        "owns": ["RiskAssessment", "Challenge", "BlockDecision", "EvidenceSummary"],
        "rationale": "Python supports rule experimentation, scoring, and evidence summarization without coupling source domains.",
    },
    {
        "id": "account",
        "domain": "Account",
        "language": "typescript",
        "runtime": "node",
        "phase": "phase-1-limited",
        "status": "skeleton",
        "priority": "P1",
        "workPackages": ["WP-18"],
        "docs": ["docs/02-domains/account.md"],
        "owns": ["UserAccount", "Session", "Preference", "Freeze", "AccountClosureSaga shell"],
        "rationale": "TypeScript is suitable for identity/session edge APIs while traveler facts remain in Traveler Profile.",
    },
    {
        "id": "admin-audit",
        "domain": "Admin & Audit",
        "language": "java",
        "runtime": "jvm",
        "phase": "phase-1-support",
        "status": "skeleton",
        "priority": "P1",
        "workPackages": ["WP-19"],
        "docs": ["docs/02-domains/admin-audit.md"],
        "owns": ["OperatorIdentity", "Approval", "ManualAction", "AuditTrail"],
        "rationale": "Java is a conservative fit for approvals, audit retention, and authorization boundaries.",
    },
    {
        "id": "customer-service",
        "domain": "Customer Service",
        "language": "typescript",
        "runtime": "node",
        "phase": "phase-1-support",
        "status": "skeleton",
        "priority": "P1",
        "workPackages": ["WP-20"],
        "docs": ["docs/02-domains/customer-service.md"],
        "owns": ["SupportCase", "EvidenceRef", "ManualActionRequest", "CaseTimeline"],
        "rationale": "TypeScript fits collaborative case APIs and UI-adjacent timelines without owning target state.",
    },
    {
        "id": "finance-settlement",
        "domain": "Finance Settlement",
        "language": "java",
        "runtime": "jvm",
        "phase": "phase-1-limited",
        "status": "skeleton",
        "priority": "P1",
        "workPackages": ["WP-21"],
        "docs": ["docs/02-domains/finance-settlement.md"],
        "owns": ["RevenueRecognition", "Reconciliation", "Invoice", "SettlementView"],
        "rationale": "Java is appropriate for financial ledgers, reconciliation workflows, and audit consistency.",
    },
    {
        "id": "reporting",
        "domain": "Reporting",
        "language": "python",
        "runtime": "python",
        "phase": "phase-1-limited",
        "status": "skeleton",
        "priority": "P2",
        "workPackages": ["WP-22"],
        "docs": ["docs/02-domains/reporting.md"],
        "owns": ["MetricDefinition", "DashboardReadModel", "FunnelView"],
        "rationale": "Python is a pragmatic fit for metric definitions, event-derived views, and analytics validation.",
    },
    {
        "id": "supplier-catalog",
        "domain": "Supplier Catalog",
        "language": "golang",
        "runtime": "go",
        "phase": "phase-1-support",
        "status": "skeleton",
        "priority": "P1",
        "workPackages": ["WP-02", "WP-11"],
        "docs": ["docs/02-domains/supplier-catalog.md"],
        "owns": ["Supplier", "Carrier", "Contract", "ProductCapability", "ExternalCode"],
        "rationale": "Go keeps supplier capability lookup compact and separate from provider runtime health.",
    },
    {
        "id": "disruption-recovery",
        "domain": "Disruption Recovery",
        "language": "python",
        "runtime": "python",
        "phase": "future-scope",
        "status": "placeholder-skeleton",
        "priority": "P1",
        "workPackages": [],
        "docs": ["docs/02-domains/disruption-recovery.md"],
        "owns": ["RecoveryCase", "RecoveryOption", "CompensationDecision"],
        "rationale": "Python fits recovery optimization and policy experimentation when this future domain is enabled.",
    },
    {
        "id": "transfer-management",
        "domain": "Transfer Management",
        "language": "python",
        "runtime": "python",
        "phase": "future-scope",
        "status": "placeholder-skeleton",
        "priority": "P1",
        "workPackages": [],
        "docs": ["docs/02-domains/transfer-management.md"],
        "owns": ["ConnectionContract", "MctVersion", "ProtectedConnection"],
        "rationale": "Python fits connection-risk calculation and later protected-transfer recovery planning.",
    },
    {
        "id": "ancillary-service",
        "domain": "Ancillary Service",
        "language": "typescript",
        "runtime": "node",
        "phase": "future-scope",
        "status": "placeholder-skeleton",
        "priority": "P1",
        "workPackages": [],
        "docs": ["docs/02-domains/ancillary-service.md"],
        "owns": ["AncillaryOffer", "AncillaryOrderItem", "ActivationPolicy"],
        "rationale": "TypeScript fits productized add-ons and bundle-facing API composition.",
    },
    {
        "id": "waitlist",
        "domain": "Waitlist",
        "language": "rust",
        "runtime": "rust",
        "phase": "future-scope",
        "status": "placeholder-skeleton",
        "priority": "P1",
        "workPackages": [],
        "docs": ["docs/02-domains/waitlist.md"],
        "owns": ["WaitlistRequest", "QueuePolicy", "FulfillmentWindow"],
        "rationale": "Rust fits fairness, mutual-exclusion, and queue-order invariants.",
    },
    {
        "id": "wallet-promotion",
        "domain": "Wallet / Promotion",
        "language": "java",
        "runtime": "jvm",
        "phase": "future-scope",
        "status": "placeholder-skeleton",
        "priority": "P1",
        "workPackages": [],
        "docs": ["docs/02-domains/wallet-promotion.md"],
        "owns": ["WalletBalance", "PromotionGrant", "Coupon", "PointLedger"],
        "rationale": "Java is a conservative default for stored-value and promotion ledgers.",
    },
    {
        "id": "dispatch",
        "domain": "Dispatch",
        "language": "golang",
        "runtime": "go",
        "phase": "future-scope",
        "status": "placeholder-skeleton",
        "priority": "P1",
        "workPackages": [],
        "docs": ["docs/02-domains/dispatch.md"],
        "owns": ["RideRequest", "RideAssignment", "DriverLifecycle", "Eta"],
        "rationale": "Go fits real-time dispatch APIs, adapter calls, and low-latency state updates.",
    },
]


WORK_PACKAGES = [
    ("REQ-101", "WP-01 Shared kernel contracts", "Define common IDs, value objects, and event envelope.", "P0", []),
    ("REQ-102", "WP-02 Place and network master data", "Represent places, transport nodes, and provider place mappings.", "P0", ["REQ-101"]),
    ("REQ-103", "WP-03 Service plan schedules", "Represent routes, timetables, calendars, planned suspension, and plan versions.", "P0", ["REQ-102"]),
    ("REQ-104", "WP-04 Fare and rule snapshots", "Represent fare rule sets, quotes, refund fees, change fees, and rule snapshots.", "P0", ["REQ-102", "REQ-103"]),
    ("REQ-105", "WP-05 Internal segment inventory", "Represent inventory pools, capacity holds, quotas, overlap checks, and hold TTL.", "P0", ["REQ-103"]),
    ("REQ-106", "WP-06 Trip planning query candidates", "Search without locking inventory and explain unavailable or unreachable journeys.", "P0", ["REQ-102", "REQ-103", "REQ-104", "REQ-105"]),
    ("REQ-107", "WP-07 Offer snapshots", "Freeze offer, offer item, price snapshot, disclosure, and TTL without holding inventory.", "P0", ["REQ-104", "REQ-105", "REQ-106"]),
    ("REQ-108", "WP-08 Journey order aggregate", "Create commercial orders that reference offers without owning inventory, payment, or ticketing.", "P0", ["REQ-107"]),
    ("REQ-109", "WP-09 Booking saga", "Coordinate segment bookings, provider reservation mapping, and compensation status.", "P0", ["REQ-105", "REQ-108", "REQ-111"]),
    ("REQ-110", "WP-10 Payment cash loop", "Create payment intents, refunds, callback records, late payment handling, and idempotency keys.", "P0", ["REQ-108"]),
    ("REQ-111", "WP-11 Provider integration ACL", "Normalize provider/channel adapters, signatures, raw archive, and external status mapping.", "P0", ["REQ-101"]),
    ("REQ-112", "WP-12 Ticket entitlement", "Issue, void, suspend, and board main-ticket entitlements after all prerequisites are satisfied.", "P0", ["REQ-109", "REQ-110"]),
    ("REQ-113", "WP-13 Fulfillment facts", "Record boarding, check-in, no-show, and offline evidence disputes.", "P1", ["REQ-112"]),
    ("REQ-114", "WP-14 Post-sales refund and change", "Evaluate and execute refund/change cases without delete-and-recreate semantics.", "P0", ["REQ-104", "REQ-105", "REQ-109", "REQ-110", "REQ-112"]),
    ("REQ-115", "WP-15 Transaction notification", "Schedule required transaction notifications with idempotency and delivery receipts.", "P1", ["REQ-108", "REQ-110", "REQ-112", "REQ-114"]),
    ("REQ-116", "WP-16 Traveler facts", "Represent travelers, documents, eligibility summaries, and preference snapshots.", "P0", ["REQ-101"]),
    ("REQ-117", "WP-17 Risk decisions", "Represent risk assessments, challenges, block/allow decisions, and evidence summaries.", "P1", ["REQ-108", "REQ-110", "REQ-116"]),
    ("REQ-118", "WP-18 Account minimum", "Represent account, session, preference, freeze, and account-closure shell behavior.", "P1", ["REQ-115", "REQ-116"]),
    ("REQ-119", "WP-19 Admin and audit commands", "Represent operator identity, approval, manual action, and audit trails.", "P1", ["REQ-101"]),
    ("REQ-120", "WP-20 Customer service cases", "Represent support cases, evidence references, manual action requests, and timelines.", "P1", ["REQ-108", "REQ-110", "REQ-112", "REQ-114", "REQ-119"]),
    ("REQ-121", "WP-21 Finance event consumption", "Consume order/payment/post-sales events for revenue recognition and reconciliation views.", "P1", ["REQ-108", "REQ-110", "REQ-114"]),
    ("REQ-122", "WP-22 Reporting read models", "Expose read-only metrics and dashboards from events and Finance views.", "P2", ["REQ-106", "REQ-108", "REQ-110", "REQ-112", "REQ-121"]),
    ("REQ-123", "WP-23 Legacy ACL strangler", "Map old preserve, cancel, rebook, payment, and execute entrypoints to controlled commands.", "P1", ["REQ-108", "REQ-109", "REQ-110", "REQ-112", "REQ-114", "REQ-119"]),
]


def clean_pkg(value: str) -> str:
    return re.sub(r"[^a-z0-9_]", "_", value.lower()).strip("_")


def java_pkg(value: str) -> str:
    return re.sub(r"[^a-z0-9]", "", value.lower())


def rust_crate(value: str) -> str:
    return re.sub(r"[^a-z0-9_]", "_", value.lower().replace("-", "_")).strip("_")


def write(path: str, content: str) -> None:
    target = ROOT / path
    target.parent.mkdir(parents=True, exist_ok=True)
    if not content.endswith("\n"):
        content += "\n"
    target.write_text(content, encoding="utf-8")


def catalog_path(entry: dict) -> str:
    return entry.get("path", f"services/{entry['id']}")


def readme(entry: dict) -> str:
    docs = "\n".join(f"- `{doc}`" for doc in entry["docs"])
    owns = "\n".join(f"- {item}" for item in entry["owns"])
    commands = {
        "golang": "go test ./...",
        "java": "mvn test",
        "python": "PYTHONPATH=src python3 -m unittest discover -s tests",
        "rust": "cargo test",
        "typescript": "npm test",
    }[entry["language"]]
    return f"""# {entry['id']}

Domain: {entry['domain']}

Language: {entry['language']}

Phase: {entry['phase']}

Status: {entry['status']}

## Owns

{owns}

## DDD Sources

{docs}

## Language Rationale

{entry['rationale']}

## Skeleton Check

```bash
{commands}
```
"""


def go_files(entry: dict) -> None:
    service_id = entry["id"]
    path = catalog_path(entry)
    module = f"github.com/trainticket/greenfield/services/{service_id}"
    work_packages = ", ".join(entry["workPackages"]) or "future"
    owns = ", ".join(entry["owns"])
    write(f"{path}/go.mod", f"""module {module}

go {GO_DIRECTIVE}

require github.com/gin-gonic/gin {GIN_VERSION}
""")
    write(f"{path}/cmd/{service_id}/main.go", f"""package main

import (
	"log"
	"os"

	apphttp "{module}/internal/http"
)

func main() {{
	port := os.Getenv("PORT")
	if port == "" {{
		port = "8080"
	}}
	if err := apphttp.Router().Run(":" + port); err != nil {{
		log.Fatal(err)
	}}
}}
""")
    write(f"{path}/internal/domain/profile.go", f"""package domain

type ServiceProfile struct {{
	ServiceID    string   `json:"serviceId"`
	Domain       string   `json:"domain"`
	Language     string   `json:"language"`
	Phase        string   `json:"phase"`
	WorkPackages []string `json:"workPackages"`
	Owns         []string `json:"owns"`
}}

func Profile() ServiceProfile {{
	return ServiceProfile{{
		ServiceID:    "{service_id}",
		Domain:       "{entry['domain']}",
		Language:     "golang",
		Phase:        "{entry['phase']}",
		WorkPackages: []string{{{", ".join(json.dumps(wp) for wp in entry["workPackages"])}}},
		Owns:         []string{{{", ".join(json.dumps(item) for item in entry["owns"])}}},
	}}
}}

func Health() string {{
	return "ok"
}}

const WorkPackageSummary = "{work_packages}"
const OwnershipSummary = "{owns}"
""")
    write(f"{path}/internal/http/router.go", f"""package http

import (
	"net/http"

	"github.com/gin-gonic/gin"

	"{module}/internal/domain"
)

func Router() *gin.Engine {{
	gin.SetMode(gin.ReleaseMode)
	router := gin.New()
	router.GET("/health", func(ctx *gin.Context) {{
		ctx.JSON(http.StatusOK, gin.H{{"status": domain.Health(), "service": domain.Profile()}})
	}})
	return router
}}
""")
    write(f"{path}/internal/domain/profile_test.go", f"""package domain

import "testing"

func TestSkeletonProfileMatchesDomain(t *testing.T) {{
	profile := Profile()
	if profile.ServiceID != "{service_id}" {{
		t.Fatalf("unexpected service id: %s", profile.ServiceID)
	}}
	if profile.Domain != "{entry['domain']}" {{
		t.Fatalf("unexpected domain: %s", profile.Domain)
	}}
	if Health() != "ok" {{
		t.Fatalf("unexpected health value")
	}}
}}
""")
    write(f"{path}/internal/http/router_test.go", f"""package http

import (
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/gin-gonic/gin"
)

func TestHealthEndpoint(t *testing.T) {{
	gin.SetMode(gin.TestMode)
	recorder := httptest.NewRecorder()
	request := httptest.NewRequest(http.MethodGet, "/health", nil)

	Router().ServeHTTP(recorder, request)

	if recorder.Code != http.StatusOK {{
		t.Fatalf("unexpected status: %d", recorder.Code)
	}}
}}
""")


def python_files(entry: dict) -> None:
    path = catalog_path(entry)
    package = clean_pkg(entry["id"])
    write(f"{path}/pyproject.toml", f"""[project]
name = "{entry['id']}"
version = "0.1.0"
description = "{entry['domain']} service skeleton"
requires-python = ">=3.11"
dependencies = [
  "fastapi=={FASTAPI_VERSION}",
]

[dependency-groups]
dev = [
  "pytest=={PYTEST_VERSION}",
]

[build-system]
requires = ["uv_build>={UV_BUILD_VERSION},<0.12"]
build-backend = "uv_build"
""")
    write(f"{path}/src/{package}/__init__.py", f'''from typing import List, TypedDict

from fastapi import FastAPI


class ServiceProfile(TypedDict):
    service_id: str
    domain: str
    language: str
    phase: str
    work_packages: List[str]
    owns: List[str]


SERVICE_PROFILE: ServiceProfile = {{
    "service_id": "{entry['id']}",
    "domain": "{entry['domain']}",
    "language": "python",
    "phase": "{entry['phase']}",
    "work_packages": {json.dumps(entry["workPackages"])},
    "owns": {json.dumps(entry["owns"])},
}}


def health() -> str:
    return "ok"


def profile() -> ServiceProfile:
    return dict(SERVICE_PROFILE)  # type: ignore[return-value]


def create_app() -> FastAPI:
    app = FastAPI(title="{entry['domain']}", version="0.1.0")

    @app.get("/health")
    def health_endpoint() -> dict[str, object]:
        return {{"status": health(), "service": profile()}}

    return app
''')
    write(f"{path}/tests/test_skeleton.py", f'''import unittest

from {package} import create_app, health, profile


class SkeletonTest(unittest.TestCase):
    def test_profile_matches_domain(self) -> None:
        service_profile = profile()
        self.assertEqual(service_profile["service_id"], "{entry['id']}")
        self.assertEqual(service_profile["domain"], "{entry['domain']}")
        self.assertEqual(health(), "ok")

    def test_fastapi_health_route_is_registered(self) -> None:
        app = create_app()
        routes = {{route.path for route in app.routes}}
        self.assertIn("/health", routes)


if __name__ == "__main__":
    unittest.main()
''')


def java_files(entry: dict) -> None:
    path = catalog_path(entry)
    package_tail = java_pkg(entry["id"])
    package_path = f"com/trainticket/{package_tail}"
    package_name = f"com.trainticket.{package_tail}"
    project_name = xml_escape(f"{entry['domain']} service skeleton")
    work_packages = ", ".join(entry["workPackages"])
    owns = ", ".join(entry["owns"])
    write(f"{path}/pom.xml", f"""<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>{SPRING_BOOT_VERSION}</version>
    <relativePath/>
  </parent>
  <groupId>com.trainticket</groupId>
  <artifactId>{entry['id']}</artifactId>
  <version>0.1.0-SNAPSHOT</version>
  <name>{project_name}</name>
  <properties>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    <java.version>{JAVA_VERSION}</java.version>
  </properties>
  <dependencies>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-test</artifactId>
      <scope>test</scope>
    </dependency>
  </dependencies>
  <build>
    <plugins>
      <plugin>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-maven-plugin</artifactId>
      </plugin>
    </plugins>
  </build>
</project>
""")
    write(f"{path}/src/main/java/{package_path}/ServiceProfile.java", f"""package {package_name};

public record ServiceProfile(
    String serviceId,
    String domain,
    String language,
    String phase,
    String workPackages,
    String owns
) {{
    public String health() {{
        return "ok";
    }}
}}
""")
    write(f"{path}/src/main/java/{package_path}/Application.java", f"""package {package_name};

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class Application {{

    public static void main(String[] args) {{
        SpringApplication.run(Application.class, args);
    }}

    public static ServiceProfile profile() {{
        return new ServiceProfile(
            "{entry['id']}",
            "{entry['domain']}",
            "java",
            "{entry['phase']}",
            "{work_packages}",
            "{owns}"
        );
    }}
}}
""")
    write(f"{path}/src/main/java/{package_path}/HealthController.java", f"""package {package_name};

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {{
    @GetMapping("/health")
    public Map<String, Object> health() {{
        return Map.of("status", "ok", "service", Application.profile());
    }}
}}
""")
    write(f"{path}/src/test/java/{package_path}/ApplicationTest.java", f"""package {package_name};

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.util.Map;
import org.junit.jupiter.api.Test;

class ApplicationTest {{
    @Test
    void healthEndpointReturnsServiceProfile() {{
        Map<String, Object> response = new HealthController().health();

        assertEquals("ok", response.get("status"));
        ServiceProfile profile = assertInstanceOf(ServiceProfile.class, response.get("service"));
        assertEquals("{entry['id']}", profile.serviceId());
    }}
}}
""")
    write(f"{path}/src/test/java/{package_path}/ApplicationContractCheck.java", f"""package {package_name};

public final class ApplicationContractCheck {{
    private ApplicationContractCheck() {{
    }}

    public static void main(String[] args) {{
        ServiceProfile profile = Application.profile();
        if (!"{entry['id']}".equals(profile.serviceId())) {{
            throw new IllegalStateException("unexpected service id: " + profile.serviceId());
        }}
        if (!"ok".equals(profile.health())) {{
            throw new IllegalStateException("unexpected health value");
        }}
    }}
}}
""")


def rust_files(entry: dict) -> None:
    path = catalog_path(entry)
    crate = rust_crate(entry["id"])
    write(f"{path}/Cargo.toml", f"""[package]
name = "{entry['id']}"
version = "0.1.0"
edition = "{RUST_EDITION}"

[lib]
name = "{crate}"
path = "src/lib.rs"

[dependencies]
axum = "{AXUM_VERSION}"
""")
    write(f"{path}/src/lib.rs", f"""use axum::{{routing::get, Router}};

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ServiceProfile {{
    pub service_id: &'static str,
    pub domain: &'static str,
    pub language: &'static str,
    pub phase: &'static str,
    pub work_packages: &'static [&'static str],
    pub owns: &'static [&'static str],
}}

pub fn profile() -> ServiceProfile {{
    ServiceProfile {{
        service_id: "{entry['id']}",
        domain: "{entry['domain']}",
        language: "rust",
        phase: "{entry['phase']}",
        work_packages: &[{", ".join(json.dumps(wp) for wp in entry["workPackages"])}],
        owns: &[{", ".join(json.dumps(item) for item in entry["owns"])}],
    }}
}}

pub fn health() -> &'static str {{
    "ok"
}}

pub fn router() -> Router {{
    Router::new().route("/health", get(health_handler))
}}

async fn health_handler() -> &'static str {{
    health()
}}

#[cfg(test)]
mod tests {{
    use super::*;

    #[test]
    fn skeleton_profile_matches_domain() {{
        let profile = profile();
        assert_eq!(profile.service_id, "{entry['id']}");
        assert_eq!(profile.domain, "{entry['domain']}");
        assert_eq!(health(), "ok");
    }}

    #[test]
    fn axum_router_can_be_constructed() {{
        let _router = router();
    }}
}}
""")
    write(f"{path}/tests/skeleton.rs", f"""use {crate}::{{health, profile, router}};

#[test]
fn profile_exports_contract_metadata() {{
    let profile = profile();
    assert_eq!(profile.service_id, "{entry['id']}");
    assert_eq!(health(), "ok");
    let _router = router();
}}
""")


def typescript_files(entry: dict) -> None:
    path = catalog_path(entry)
    write(f"{path}/package.json", json.dumps({
        "name": f"@trainticket/{entry['id']}",
        "version": "0.1.0",
        "private": True,
        "type": "module",
        "scripts": {
            "build": "tsc --noEmit",
            "test": "npm run build",
        },
        "dependencies": {
            "fastify": FASTIFY_VERSION,
        },
        "devDependencies": {
            "@types/node": TYPES_NODE_VERSION,
            "typescript": TYPESCRIPT_VERSION,
        },
    }, indent=2))
    write(f"{path}/tsconfig.json", json.dumps({
        "compilerOptions": {
            "target": "ES2022",
            "module": "NodeNext",
            "moduleResolution": "NodeNext",
            "strict": True,
            "noEmit": True,
            "skipLibCheck": True,
            "rootDir": "src",
        },
        "include": ["src/**/*.ts"],
    }, indent=2))
    write(f"{path}/src/index.ts", f"""import Fastify, {{ type FastifyInstance }} from "fastify";

export type ServiceProfile = {{
  serviceId: string;
  domain: string;
  language: "typescript";
  phase: string;
  workPackages: string[];
  owns: string[];
}};

export const serviceProfile: ServiceProfile = {{
  serviceId: "{entry['id']}",
  domain: "{entry['domain']}",
  language: "typescript",
  phase: "{entry['phase']}",
  workPackages: {json.dumps(entry["workPackages"])},
  owns: {json.dumps(entry["owns"])},
}};

export function health(): "ok" {{
  return "ok";
}}

export function createApp(): FastifyInstance {{
  const app = Fastify({{ logger: false }});
  app.get("/health", async () => ({{ status: health(), service: serviceProfile }}));
  return app;
}}
""")


def service_files(entry: dict) -> None:
    path = catalog_path(entry)
    write(f"{path}/README.md", readme(entry))
    language = entry["language"]
    if language == "golang":
        go_files(entry)
    elif language == "python":
        python_files(entry)
    elif language == "java":
        java_files(entry)
    elif language == "rust":
        rust_files(entry)
    elif language == "typescript":
        typescript_files(entry)
    else:
        raise ValueError(f"unsupported language: {language}")


def service_catalog() -> None:
    catalog = {
        "schemaVersion": "trainticket.service-catalog/v1",
        "project": "train-ticket-ddd-greenfield",
        "allowedLanguages": ["java", "golang", "python", "rust", "typescript"],
        "platformModules": PLATFORM_MODULES,
        "services": [
            {
                "id": service["id"],
                "path": catalog_path(service),
                **{k: v for k, v in service.items() if k != "id"},
            }
            for service in SERVICES
        ],
    }
    write("service-catalog.json", json.dumps(catalog, indent=2, ensure_ascii=True))


def go_work() -> None:
    go_services = [catalog_path(service) for service in SERVICES if service["language"] == "golang"]
    body = "\n".join(f"\t./{path}" for path in go_services)
    write("go.work", f"""go {GO_DIRECTIVE}

use (
{body}
)
""")


def service_readme() -> None:
    rows = []
    for service in SERVICES:
        rows.append(
            f"| `{service['id']}` | {service['domain']} | {service['language']} | {service['phase']} | {', '.join(service['workPackages']) or 'future'} |"
        )
    write("services/README.md", f"""# Service Skeletons

This directory contains one service skeleton per DDD bounded context.

The `Work Package` column is retained from the historical skeleton generator for
traceability only. Old `WP-01` / `WP-xx` identifiers are not the current rewrite
backlog unless a new plan explicitly regenerates and approves them.

| Service | Domain | Language | Phase | Work Package |
|---|---|---|---|---|
{chr(10).join(rows)}

The root `service-catalog.json` is the machine-readable source of this table.
""")
    write("platform/README.md", """# Platform Modules

Platform modules hold cross-service contracts and reference implementations.
They are not business services and must not own bounded-context state.
""")


def architecture_docs() -> None:
    rows = []
    for entry in PLATFORM_MODULES + SERVICES:
        rows.append(
            f"| `{entry['id']}` | {entry['domain']} | {entry['language']} | {entry['phase']} | {entry['rationale']} |"
        )
    write("docs/05-service-architecture/README.md", """# Service Architecture

This directory records the initial polyglot microservice skeleton derived from the DDD final baseline.

| Document | Purpose |
|---|---|
| `language-selection.md` | Language assignment by bounded context and the rationale for each choice. |

The authoritative DDD inputs remain under `docs/03-ddd-final/` and `docs/02-domains/`.
""")
    write("docs/05-service-architecture/language-selection.md", f"""# Polyglot Language Selection

Last updated: 2026-06-28

## Decision Rules

1. Transactional aggregates, money, approvals, and audit-heavy workflows default to Java.
2. Protocol adapters, event ingestion, master-data lookup, and low-latency I/O default to Go.
3. Rule engines, planning, scoring, recovery, and analytics default to Python.
4. Strong invariant kernels such as inventory, entitlement, and fair queues default to Rust.
5. User-facing collaboration and payload-composition services default to TypeScript.

## Runtime And Framework Baseline

| Language | Runtime / Build | Service Framework | Version Policy |
|---|---|---|---|
| Java | Temurin JDK {JAVA_VERSION}, Maven | Spring Boot {SPRING_BOOT_VERSION} | Pin current latest stable parent in each service POM. |
| Go | Go {GO_DIRECTIVE} module directive, official Go {GO_DIRECTIVE}.x toolchain | Gin {GIN_VERSION} | Pin current latest module version in `go.mod`; lock via `go.sum`. |
| Python | Python >=3.11, uv | FastAPI {FASTAPI_VERSION} | Pin current latest packages in `pyproject.toml`; lock via `uv.lock`. |
| Rust | rustup stable, Cargo edition {RUST_EDITION} | Axum {AXUM_VERSION} | Pin current latest crate version in `Cargo.toml`; lock via `Cargo.lock`. |
| TypeScript | Node 26, npm | Fastify {FASTIFY_VERSION}, TypeScript {TYPESCRIPT_VERSION} | Pin current latest npm versions in `package.json`; lock via `package-lock.json`. |

## Assignments

| Service | Domain | Language | Phase | Rationale |
|---|---|---|---|---|
{chr(10).join(rows)}

## Boundaries

- Future-scope services are initialized only as placeholders. They must not be wired into Phase 1 acceptance gates until the DDD scope file changes.
- Shared Kernel is represented as a Rust reference module plus language-neutral contracts. Domain event payloads remain owned by their producing bounded context.
- A service may change language later only through a short architecture decision that updates this file and `service-catalog.json`.
""")


def yaml_string(value: str) -> str:
    return json.dumps(value)


def project_index() -> None:
    lines = [
        "project:",
        "  name: train-ticket-ddd-greenfield",
        "  languages: [java, golang, python, rust, typescript]",
        "",
        "# Current framing: this repository is being prepared for a full rewrite /",
        "# greenfield rebuild. Only REQ-001 and REQ-002 describe the current skeleton and",
        "# devcontainer state. REQ-101 through REQ-123 are retained as legacy WP-derived",
        "# references for traceability; they are not the current execution backlog unless",
        "# explicitly regenerated and re-approved. See docs/00-current-status.md.",
        "requirements:",
        "  - id: REQ-001",
        "    title: \"Polyglot bounded-context skeleton\"",
        "    description: \"Initialize one service skeleton per DDD bounded context and one platform shared-kernel module.\"",
        "    priority: P0",
        "    status: tested",
        "    code:",
        "      - path: service-catalog.json",
        "        description: \"Machine-readable service ownership and language catalog.\"",
        "      - path: services",
        "        description: \"Bounded-context service skeletons.\"",
        "      - path: platform/shared-kernel-rust",
        "        description: \"Shared Kernel reference skeleton.\"",
        "    tests:",
        "      - path: scripts/check-skeleton.py",
        "        description: \"Validates catalog, service files, and available language checks.\"",
        "    docs:",
        "      - path: docs/05-service-architecture/language-selection.md",
        "    depends_on: []",
        "    confidence: confirmed",
        "    source: \"docs/03-ddd-final/domain-reduce-status.md, docs/03-ddd-final/implementation-roadmap.md\"",
        "",
        "  - id: REQ-002",
        "    title: \"Polyglot devcontainer toolchain\"",
        "    description: \"The development container includes the runtime tools needed by the selected Java, Go, Python, Rust, and TypeScript skeletons.\"",
        "    priority: P0",
        "    status: implemented",
        "    code:",
        "      - path: .devcontainer/Dockerfile",
        "        description: \"Installs language toolchains and operational CLI tools.\"",
        "      - path: .devcontainer/scripts/check.sh",
        "        description: \"Reports available toolchain versions and validates the repository layout.\"",
        "    tests:",
        "      - path: .devcontainer/scripts/check.sh",
        "        description: \"Smoke check for documentation and toolchain availability.\"",
        "    docs:",
        "      - path: .devcontainer/README.md",
        "    depends_on: [REQ-001]",
        "    confidence: confirmed",
        "    source: \".devcontainer/README.md\"",
        "",
    ]
    lines.extend(
        [
            "  # Legacy WP-derived references below. Do not use as active backlog without",
            "  # regenerating the rewrite plan.",
        ]
    )
    for req_id, title, description, priority, depends_on in WORK_PACKAGES:
        lines.extend(
            [
                f"  - id: {req_id}",
                f"    title: {yaml_string(title)}",
                f"    description: {yaml_string(description)}",
                f"    priority: {priority}",
                "    status: legacy-reference",
                "    code: []",
                "    tests: []",
                "    docs:",
                "      - path: docs/03-ddd-final/implementation-roadmap.md",
                "      - path: docs/03-ddd-final/phase-1-contract.md",
                f"    depends_on: [{', '.join(depends_on)}]",
                "    confidence: confirmed",
                "    source: \"docs/03-ddd-final/implementation-roadmap.md\"",
                "",
            ]
        )
    write("project-index.yaml", "\n".join(lines))


def check_script() -> None:
    write("scripts/check-skeleton.py", r'''#!/usr/bin/env python3
from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
ALLOWED_LANGUAGES = {"java", "golang", "python", "rust", "typescript"}


def fail(message: str) -> None:
    raise SystemExit(f"skeleton check failed: {message}")


def command_ok(command: list[str]) -> bool:
    try:
        subprocess.run(command, cwd=ROOT, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=True)
        return True
    except (OSError, subprocess.CalledProcessError):
        return False


def require_tool(name: str, strict: bool) -> bool:
    if shutil.which(name):
        return True
    if strict:
        fail(f"required tool is missing in strict mode: {name}")
    return False


def run(command: list[str], cwd: Path, env: dict[str, str] | None = None) -> None:
    print(f"+ ({cwd.relative_to(ROOT)}) {' '.join(command)}")
    subprocess.run(command, cwd=cwd, env=env, check=True)


def require(path: Path) -> None:
    if not path.exists():
        fail(f"missing required path: {path.relative_to(ROOT)}")


def entries(catalog: dict) -> list[dict]:
    return list(catalog.get("platformModules", [])) + list(catalog.get("services", []))


def validate_catalog(catalog: dict) -> list[dict]:
    if catalog.get("schemaVersion") != "trainticket.service-catalog/v1":
        fail("unexpected service-catalog schemaVersion")
    services = entries(catalog)
    seen: set[str] = set()
    for service in services:
        service_id = service["id"]
        if service_id in seen:
            fail(f"duplicate service id: {service_id}")
        seen.add(service_id)
        language = service["language"]
        if language not in ALLOWED_LANGUAGES:
            fail(f"unsupported language for {service_id}: {language}")
        root = ROOT / service["path"]
        require(root / "README.md")
        for doc in service.get("docs", []):
            require(ROOT / doc)
        if language == "golang":
            require(root / "go.mod")
            require(root / "internal/domain/profile.go")
        elif language == "java":
            require(root / "pom.xml")
            require(root / "src/main")
        elif language == "python":
            require(root / "pyproject.toml")
            require(root / "src")
            require(root / "tests")
        elif language == "rust":
            require(root / "Cargo.toml")
            require(root / "src/lib.rs")
        elif language == "typescript":
            require(root / "package.json")
            require(root / "tsconfig.json")
            require(root / "src/index.ts")
    return services


def java_contract_check_class(service_root: Path) -> str:
    candidates = list(service_root.glob("src/test/java/**/ApplicationContractCheck.java"))
    if len(candidates) != 1:
        fail(f"expected one Java ApplicationContractCheck under {service_root.relative_to(ROOT)}")
    package_name = ""
    for line in candidates[0].read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line.startswith("package ") and line.endswith(";"):
            package_name = line.removeprefix("package ").removesuffix(";")
            break
    if not package_name:
        fail(f"missing package declaration in {candidates[0].relative_to(ROOT)}")
    return f"{package_name}.ApplicationContractCheck"


def run_available_language_checks(services: list[dict], strict: bool) -> None:
    if require_tool("go", strict):
        for service in services:
            if service["language"] == "golang":
                service_root = ROOT / service["path"]
                if strict:
                    run(["go", "mod", "tidy"], service_root)
                run(["go", "test", "./..."], service_root)
    else:
        print("skip go checks: go not found")

    for service in services:
        if service["language"] == "python":
            service_root = ROOT / service["path"]
            if require_tool("uv", strict):
                if strict:
                    run(["uv", "sync"], service_root)
                run(["uv", "run", "python", "-m", "unittest", "discover", "-s", "tests"], service_root)
            else:
                env = os.environ.copy()
                env["PYTHONPATH"] = str(service_root / "src")
                run([sys.executable, "-m", "unittest", "discover", "-s", "tests"], service_root, env=env)

    if require_tool("cargo", strict):
        for service in services:
            if service["language"] == "rust":
                run(["cargo", "test", "--quiet"], ROOT / service["path"])
    else:
        print("skip rust checks: cargo not found")

    if require_tool("mvn", strict) and require_tool("java", strict) and command_ok(["java", "-version"]):
        for service in services:
            if service["language"] == "java":
                service_root = ROOT / service["path"]
                run(["mvn", "-q", "test"], service_root)
                run(["java", "-cp", "target/classes:target/test-classes", java_contract_check_class(service_root)], service_root)
    else:
        print("skip java checks: java or maven not available")

    for service in services:
        if service["language"] != "typescript":
            continue
        service_root = ROOT / service["path"]
        if strict:
            require_tool("npm", strict)
            run(["npm", "install"], service_root)
        if (service_root / "node_modules/.bin/tsc").exists() or require_tool("tsc", strict):
            run(["npm", "test"], service_root)
        else:
            print(f"skip typescript build for {service['id']}: tsc not available")


def main() -> None:
    strict = "--strict" in sys.argv[1:]
    require(ROOT / "project-index.yaml")
    require(ROOT / "docs/05-service-architecture/language-selection.md")
    catalog = json.loads((ROOT / "service-catalog.json").read_text(encoding="utf-8"))
    services = validate_catalog(catalog)
    run_available_language_checks(services, strict)
    print(f"skeleton check passed: {len(services)} modules")


if __name__ == "__main__":
    main()
''')


def list_services_script() -> None:
    write("scripts/list-services.py", r'''#!/usr/bin/env python3
from __future__ import annotations

import json
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def main() -> None:
    catalog = json.loads((ROOT / "service-catalog.json").read_text(encoding="utf-8"))
    for service in catalog["platformModules"] + catalog["services"]:
        print(f"{service['id']}\t{service['language']}\t{service['phase']}\t{service['path']}")


if __name__ == "__main__":
    main()
''')


def makefile() -> None:
    write("Makefile", """SHELL := /usr/bin/env bash

.PHONY: check check-strict skeleton-check list-services

check: skeleton-check

check-strict:
	python3 scripts/check-skeleton.py --strict

skeleton-check:
	python3 scripts/check-skeleton.py

list-services:
	python3 scripts/list-services.py
""")


def main() -> None:
    service_catalog()
    go_work()
    service_readme()
    architecture_docs()
    project_index()
    check_script()
    list_services_script()
    makefile()
    for entry in PLATFORM_MODULES + SERVICES:
        service_files(entry)


if __name__ == "__main__":
    main()
