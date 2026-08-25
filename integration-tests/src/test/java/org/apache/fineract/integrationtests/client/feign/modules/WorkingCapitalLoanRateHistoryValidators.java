/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.fineract.integrationtests.client.feign.modules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import org.apache.fineract.client.models.WorkingCapitalLoanPeriodPaymentRateChangeData;

/**
 * Assertions for the EIR / calculated-values snapshot carried by each working-capital-loan period payment rate change,
 * as returned by {@code GET .../rate-changes} and by the {@code periodPaymentRateHistory} array in fetch-loan-details.
 */
public final class WorkingCapitalLoanRateHistoryValidators {

    private WorkingCapitalLoanRateHistoryValidators() {}

    /**
     * Finds the (single) history entry whose {@code newRate} equals the given rate. Rates are unique per scenario,
     * which makes them a stable key even when two changes share an effective date (same-date overwrite).
     */
    public static WorkingCapitalLoanPeriodPaymentRateChangeData entryByNewRate(
            final List<WorkingCapitalLoanPeriodPaymentRateChangeData> history, final BigDecimal newRate) {
        final List<WorkingCapitalLoanPeriodPaymentRateChangeData> matches = history.stream()
                .filter(entry -> entry.getNewRate() != null && newRate.compareTo(entry.getNewRate()) == 0).toList();
        assertEquals(1, matches.size(),
                "Expected exactly one rate-change entry with newRate=" + newRate + " but found " + matches.size() + " in: " + history);
        return matches.getFirst();
    }

    /**
     * Asserts the full calculated-values snapshot of one rate-change entry: daily EIR, annualized EIR, net disbursement
     * (remaining balance at the change), discount, daily payment amount and the segment's term in days. Expected values
     * are the engine's own as-booked {@code RateSegment} numbers, written once at booking time and never restated - the
     * annualized EIR among them, persisted at six decimals.
     */
    public static void validateSnapshot(final String label, final WorkingCapitalLoanPeriodPaymentRateChangeData entry,
            final String expectedEir, final String expectedAnnualEir, final String expectedNetDisbursement, final String expectedDiscount,
            final String expectedDailyPayment, final int expectedSegmentTotalDays) {
        validateDecimal(label, "eir", entry.getEir(), expectedEir);
        validateDecimal(label, "calculatedAnnualEir", entry.getCalculatedAnnualEir(), expectedAnnualEir);
        validateDecimal(label, "netDisbursementAmount", entry.getNetDisbursementAmount(), expectedNetDisbursement);
        validateDecimal(label, "discountAmount", entry.getDiscountAmount(), expectedDiscount);
        validateDecimal(label, "dailyPaymentAmount", entry.getDailyPaymentAmount(), expectedDailyPayment);
        assertEquals(expectedSegmentTotalDays, entry.getSegmentTotalDays(), label + ": 'segmentTotalDays'");
    }

    /**
     * Asserts a numeric field equals the expected decimal, comparing at the expected value's scale (HALF_UP) so an
     * implementation carrying more precision than asserted (the 6-dp columns returning {@code 8060.040000}) still
     * matches.
     */
    public static void validateDecimal(final String label, final String field, final BigDecimal actual, final String expected) {
        assertNotNull(actual, label + ": expected '" + field + "' = " + expected + " but it was null");
        final BigDecimal expectedValue = new BigDecimal(expected);
        final BigDecimal rounded = actual.setScale(expectedValue.scale(), RoundingMode.HALF_UP);
        assertEquals(0, expectedValue.compareTo(rounded),
                label + ": '" + field + "' expected " + expected + " (compared at scale " + expectedValue.scale() + ") but was " + actual);
    }
}
