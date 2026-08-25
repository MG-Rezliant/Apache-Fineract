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
package org.apache.fineract.infrastructure.event.external.service.serialization.mapper.workingcapitalloan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.apache.fineract.avro.workingcapitalloan.v1.WorkingCapitalLoanTransactionDataV1;
import org.apache.fineract.avro.workingcapitalloan.v1.WorkingCapitalLoanTransactionTypeDataV1;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTransactionType;
import org.apache.fineract.portfolio.loanproduct.service.LoanEnumerations;
import org.apache.fineract.portfolio.workingcapitalloan.data.WorkingCapitalLoanTransactionData;
import org.junit.jupiter.api.Test;

class WorkingCapitalLoanTransactionDataMapperTest {

    private final WorkingCapitalLoanTransactionDataMapper mapper = new WorkingCapitalLoanTransactionDataMapper() {

        @Override
        public WorkingCapitalLoanTransactionDataV1 map(final WorkingCapitalLoanTransactionData source) {
            return null;
        }
    };

    @Test
    void toTransactionType_nullSource_returnsNull() {
        assertNull(mapper.toTransactionType(null));
    }

    @Test
    void toTransactionType_repayment_setsRepaymentAndRepaymentType() {
        final WorkingCapitalLoanTransactionTypeDataV1 result = mapper
                .toTransactionType(LoanEnumerations.transactionType(LoanTransactionType.REPAYMENT));

        assertNotNull(result);
        assertEquals(LoanTransactionType.REPAYMENT.name(), result.getId());
        assertEquals(LoanTransactionType.REPAYMENT.getCode(), result.getCode());
        assertEquals("Repayment", result.getValue());

        assertEquals(Boolean.TRUE, result.getRepayment());
        assertEquals(Boolean.TRUE, result.getRepaymentType());
        assertEquals(Boolean.FALSE, result.getDisbursement());
        assertEquals(Boolean.FALSE, result.getPayoutRefund());
        assertEquals(Boolean.FALSE, result.getGoodwillCredit());
        assertEquals(Boolean.FALSE, result.getCreditBalanceRefund());
        assertEquals(Boolean.FALSE, result.getChargeAdjustment());
        assertEquals(Boolean.FALSE, result.getChargeOff());
        assertEquals(Boolean.FALSE, result.getWriteOff());
        assertEquals(Boolean.FALSE, result.getAccrual());
        assertEquals(Boolean.FALSE, result.getAccrualAdjustment());
    }

    @Test
    void toTransactionType_chargeAdjustment_isAlsoRepaymentType() {
        final WorkingCapitalLoanTransactionTypeDataV1 result = mapper
                .toTransactionType(LoanEnumerations.transactionType(LoanTransactionType.CHARGE_ADJUSTMENT));

        assertNotNull(result);
        assertEquals(LoanTransactionType.CHARGE_ADJUSTMENT.name(), result.getId());
        assertEquals(Boolean.TRUE, result.getChargeAdjustment());
        assertEquals(Boolean.TRUE, result.getRepaymentType());
        assertEquals(Boolean.FALSE, result.getRepayment());
    }

    @Test
    void toTransactionType_discountFee_setsNoFlag() {
        final WorkingCapitalLoanTransactionTypeDataV1 result = mapper
                .toTransactionType(LoanEnumerations.transactionType(LoanTransactionType.DISCOUNT_FEE));

        assertNotNull(result);
        assertEquals(LoanTransactionType.DISCOUNT_FEE.name(), result.getId());
        assertEquals(LoanTransactionType.DISCOUNT_FEE.getCode(), result.getCode());
        assertEquals("Discount Fee", result.getValue());

        assertEquals(Boolean.FALSE, result.getDisbursement());
        assertEquals(Boolean.FALSE, result.getRepayment());
        assertEquals(Boolean.FALSE, result.getPayoutRefund());
        assertEquals(Boolean.FALSE, result.getGoodwillCredit());
        assertEquals(Boolean.FALSE, result.getCreditBalanceRefund());
        assertEquals(Boolean.FALSE, result.getChargeAdjustment());
        assertEquals(Boolean.FALSE, result.getRepaymentType());
        assertEquals(Boolean.FALSE, result.getChargeOff());
        assertEquals(Boolean.FALSE, result.getWriteOff());
        assertEquals(Boolean.FALSE, result.getAccrual());
        assertEquals(Boolean.FALSE, result.getAccrualAdjustment());
    }
}
