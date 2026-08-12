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
package org.apache.fineract.portfolio.workingcapitalloan.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.apache.fineract.infrastructure.core.domain.AbstractAuditableWithUTCDateTimeCustom;
import org.apache.fineract.organisation.monetary.domain.MoneyHelper;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "m_wc_loan_period_payment_rate_change")
public class WorkingCapitalLoanPeriodPaymentRateChange extends AbstractAuditableWithUTCDateTimeCustom<Long> {

    /** Two rate changes on the same loan can differ only from the ninth decimal of the EIR onwards. */
    private static final int EIR_SCALE = 12;

    private static final int AMOUNT_SCALE = 6;

    /** The annualised rate is a headline figure; six decimals match the rate columns either side of it. */
    private static final int ANNUAL_EIR_SCALE = 6;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "wc_loan_id", nullable = false)
    private WorkingCapitalLoan workingCapitalLoan;

    @Column(name = "effective_date", nullable = false)
    private LocalDate effectiveDate;

    @Column(name = "previous_rate", scale = 6, precision = 19, nullable = false)
    private BigDecimal previousRate;

    @Column(name = "new_rate", scale = 6, precision = 19, nullable = false)
    private BigDecimal newRate;

    @Column(name = "is_reversed", nullable = false)
    private boolean reversed;

    @Column(name = "reversed_on_date")
    private LocalDate reversedOnDate;

    /**
     * Snapshot of what the amortization schedule computed for the segment this change opened, taken once when the
     * change was booked. Nullable: changes booked before the snapshot existed never had one, and it cannot be
     * reconstructed from a schedule model that has since been rewritten.
     */
    @Column(name = "eir", scale = EIR_SCALE, precision = 19)
    private BigDecimal eir;

    /**
     * The daily {@link #eir} expressed annually, stored as booked rather than derived on read: the annualisation basis
     * is a product setting, and a history row has to keep answering with the number the borrower was quoted even after
     * the product changes underneath it.
     */
    @Column(name = "calculated_annual_eir", scale = ANNUAL_EIR_SCALE, precision = 19)
    private BigDecimal calculatedAnnualEir;

    @Column(name = "net_disbursement_amount", scale = AMOUNT_SCALE, precision = 19)
    private BigDecimal netDisbursementAmount;

    @Column(name = "discount_amount", scale = AMOUNT_SCALE, precision = 19)
    private BigDecimal discountAmount;

    @Column(name = "daily_payment_amount", scale = AMOUNT_SCALE, precision = 19)
    private BigDecimal dailyPaymentAmount;

    /** Payment periods the segment this change opened runs for - not the loan's term, which spans every segment. */
    @Column(name = "segment_total_days")
    private Integer segmentTotalDays;

    @Version
    private int version;

    public static WorkingCapitalLoanPeriodPaymentRateChange create(final WorkingCapitalLoan loan, final LocalDate effectiveDate,
            final BigDecimal previousRate, final BigDecimal newRate) {
        final WorkingCapitalLoanPeriodPaymentRateChange change = new WorkingCapitalLoanPeriodPaymentRateChange();
        change.workingCapitalLoan = loan;
        change.effectiveDate = effectiveDate;
        change.previousRate = previousRate;
        change.newRate = newRate;
        change.reversed = false;
        return change;
    }

    public void reverse(final LocalDate reversalDate) {
        this.reversed = true;
        this.reversedOnDate = reversalDate;
    }

    /**
     * Records the values the rebuilt amortization schedule computed for this change. Scales are fixed here rather than
     * left to the database so API responses and event payloads carry the same value whichever database the tenant runs
     * on.
     */
    public void applyCalculatedValues(final BigDecimal eir, final BigDecimal calculatedAnnualEir, final BigDecimal netDisbursementAmount,
            final BigDecimal discountAmount, final BigDecimal dailyPaymentAmount, final Integer segmentTotalDays) {
        this.eir = scaled(eir, EIR_SCALE);
        this.calculatedAnnualEir = scaled(calculatedAnnualEir, ANNUAL_EIR_SCALE);
        this.netDisbursementAmount = scaled(netDisbursementAmount, AMOUNT_SCALE);
        this.discountAmount = scaled(discountAmount, AMOUNT_SCALE);
        this.dailyPaymentAmount = scaled(dailyPaymentAmount, AMOUNT_SCALE);
        this.segmentTotalDays = segmentTotalDays;
    }

    private static BigDecimal scaled(final BigDecimal value, final int scale) {
        return value == null ? null : value.setScale(scale, MoneyHelper.getRoundingMode());
    }
}
