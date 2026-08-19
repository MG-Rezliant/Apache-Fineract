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

import java.util.Optional;
import org.apache.fineract.avro.workingcapitalloan.v1.WorkingCapitalLoanTransactionDataV1;
import org.apache.fineract.avro.workingcapitalloan.v1.WorkingCapitalLoanTransactionTypeDataV1;
import org.apache.fineract.infrastructure.event.external.service.serialization.mapper.generic.CurrencyDataMapper;
import org.apache.fineract.infrastructure.event.external.service.serialization.mapper.support.AvroMapperConfig;
import org.apache.fineract.portfolio.loanaccount.data.LoanTransactionEnumData;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTransactionType;
import org.apache.fineract.portfolio.workingcapitalloan.data.WorkingCapitalLoanTransactionData;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

@Mapper(config = AvroMapperConfig.class, uses = CurrencyDataMapper.class)
public interface WorkingCapitalLoanTransactionDataMapper {

    @Mapping(target = "reversed", expression = "java(isReversed(source))")
    @Mapping(target = "type", source = "type", qualifiedByName = "toTransactionType")
    WorkingCapitalLoanTransactionDataV1 map(WorkingCapitalLoanTransactionData source);

    default boolean isReversed(WorkingCapitalLoanTransactionData source) {
        return Boolean.TRUE.equals(source.getReversed()) || source.getReversedOnDate() != null;
    }

    @Named("toTransactionType")
    default WorkingCapitalLoanTransactionTypeDataV1 toTransactionType(final LoanTransactionEnumData type) {
        return Optional.ofNullable(type).map(LoanTransactionEnumData::getId).map(Long::intValue).map(LoanTransactionType::fromInt)
                .map(transactionType -> WorkingCapitalLoanTransactionTypeDataV1.newBuilder()//
                        .setId(transactionType.name())//
                        .setCode(transactionType.getCode())//
                        .setValue(type.getValue())//
                        .setDisbursement(transactionType.isDisbursement())//
                        .setRepayment(transactionType.isRepayment())//
                        .setPayoutRefund(transactionType.isPayoutRefund())//
                        .setGoodwillCredit(transactionType.isGoodwillCredit())//
                        .setCreditBalanceRefund(LoanTransactionType.CREDIT_BALANCE_REFUND == transactionType)//
                        .setChargeAdjustment(transactionType.isChargeAdjustment())//
                        .setRepaymentType(transactionType.isRepaymentType())//
                        .setChargeOff(transactionType.isChargeOff())//
                        .setWriteOff(transactionType.isWriteOff())//
                        .setAccrual(transactionType.isAccrual())//
                        .setAccrualAdjustment(transactionType.isAccrualAdjustment())//
                        .build())
                .orElse(null);
    }
}
