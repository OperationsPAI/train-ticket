from __future__ import annotations

from datetime import datetime
from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field


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
    dynamicAdjustments: list[PriceComponentSchema] = Field(default_factory=list)
    baseDistanceFare: dict[str, Any] | None = None
    baseFlat: dict[str, Any] | None = None
    seatClassMultiplier: dict[str, Any] | None = None
    advancePurchaseTier: dict[str, Any] | None = None
    peakAdjustment: dict[str, Any] | None = None
    dynamicCapacityAdjustment: dict[str, Any] | None = None
    total: MoneySchema


class RuleSnapshotSchema(BaseModel):
    ruleSetId: str
    ruleSetVersion: str
    capturedAt: str
    ruleIds: list[str]
    explanationCodes: list[str]
    digest: str


class EffectiveWindowSchema(BaseModel):
    startsAt: datetime
    endsAt: datetime


class FareRuleSetRuleSchema(BaseModel):
    ruleId: str = Field(..., min_length=1)
    kind: Literal["base_fare", "tax", "fee", "discount", "refund_fee", "change_fee"]
    amount: MoneySchema
    explanation: PriceExplanationSchema
    refundable: bool = True


class CreateFareRuleSetRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    supplierId: str = Field(..., min_length=1)
    contractId: str = Field(..., min_length=1)
    productCode: str = Field(..., min_length=1)
    mode: str = Field(..., min_length=1)
    channel: str = Field(..., min_length=1)
    version: str = Field(..., min_length=1)
    effectiveWindow: EffectiveWindowSchema
    rules: list[FareRuleSetRuleSchema] = Field(..., min_length=1)


class FareQuoteRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    travelerRefs: list[str] = Field(..., min_length=1)
    channel: str = Field(..., min_length=1)
    segmentRefs: list[str] = Field(..., min_length=1)
    productCode: str = Field(default="rail-standard", min_length=1)
    fareRuleRefs: list[str] | None = None
    seatClass: str = Field(default="SECOND_CLASS", min_length=1)
    distanceKm: float | None = Field(default=None, ge=0)
    departureTime: datetime | None = None


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
    model_config = ConfigDict(extra="forbid")

    purpose: Literal["REFUND", "CHANGE"]
    entitlementIds: list[str] = Field(..., min_length=1)
    journeyOrderId: str = Field(..., min_length=1)
    segmentRefs: list[str] = Field(..., min_length=1)


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
