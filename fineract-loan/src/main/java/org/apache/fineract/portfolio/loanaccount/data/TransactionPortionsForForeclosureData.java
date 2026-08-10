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
package org.apache.fineract.portfolio.loanaccount.data;

import java.math.BigDecimal;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTransactionType;

@RequiredArgsConstructor
public class TransactionPortionsForForeclosureData implements TransactionPortionsForForeclosure {

    private final LoanTransactionType transactionType;
    private final BigDecimal interestPortion;
    private final BigDecimal feeChargesPortion;
    private final BigDecimal penaltyChargesPortion;

    @Override
    public LoanTransactionType getTransactionType() {
        return transactionType;
    }

    @Override
    public BigDecimal getInterestPortion() {
        return interestPortion;
    }

    @Override
    public BigDecimal getFeeChargesPortion() {
        return feeChargesPortion;
    }

    @Override
    public BigDecimal getPenaltyChargesPortion() {
        return penaltyChargesPortion;
    }
}
