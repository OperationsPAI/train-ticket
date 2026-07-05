from __future__ import annotations

from datetime import datetime
from typing import Any, Literal

from pydantic import BaseModel, Field


# --- Error Response ---

class ErrorResponse(BaseModel):
    code: str = Field(..., description="SCREAMING_SNAKE error code")
    message: str = Field(..., description="Human-readable description")
    correlationId: str = Field(..., description="UUID v7 correlation ID")
    details: dict[str, Any] = Field(default_factory=dict)


# --- Fare Quote ---

class MoneySchema(BaseModel):
    currency: str = Field(..., description="ISO-4217 uppercase code")
    minorUnits: int = Field(..., description="Amount in minor units of currency")


class PriceExplanationSchema(BaseModel):
    code: str
    parameters: dict[str, str] = Field(default_factory=dict)


class PriceComponentSchema(BaseModel):
    ruleId: str
    amount: MoneySchema
    explanation: PriceExplanationSchema
    refundable: bool = True


class FareBreakdownSchema(BaseModel):
    baseFare: MoneySchema
    taxes: list[PriceComponentSchema] = Field(default_factory=list)
    fees: list[PriceComponentSchema] = Field(default_factory=list)
    discounts: list[PriceComponentSchema] = Field(default_factory=list)
    total: MoneySchema


class RuleSnapshotSchema(BaseModel):
    ruleSetId: str
    ruleSetVersion: str
    capturedAt: str
    ruleIds: list[str]
    explanationCodes: list[str]
    digest: str


class FareQuoteRequest(BaseModel):
    travelerRefs: list[str] = Field(..., min_length=1)
    channel: str = Field(..., min_length=1)
    segmentRefs: list[str] = Field(..., min_length=1)
    fareRuleRefs: list[str] | None = None


class FareQuoteResponse(BaseModel):
    quoteId: str
    status: Literal["QUOTED", "FAILED"]
    breakdown: FareBreakdownSchema | None = None
    ruleSnapshot: RuleSnapshotSchema | None = None
    validFrom: str
    validUntil: str
    failedReason: str | None = None


# --- Adjustment Quote ---

class AdjustmentQuoteRequest(BaseModel):
    purpose: Literal["REFUND", "CHANGE"]
    entitlementIds: list[str] = Field(..., min_length=1)
    journeyOrderId: str = Field(..., min_length=1)


class FeeAssessmentSchema(BaseModel):
    assessmentId: str
    purpose: str
    assessedAt: str
    originalQuoteId: str
    fee: MoneySchema | None = None
    currency: str
    failedReason: str | None = None
    succeeded: bool


class AdjustmentQuoteResponse(BaseModel):
    adjustmentQuoteId: str
    purpose: str
    status: Literal["QUOTED", "FAILED"]
    refundableAmount: MoneySchema
    amountDue: MoneySchema
    validUntil: str
    failedReason: str | None = None
