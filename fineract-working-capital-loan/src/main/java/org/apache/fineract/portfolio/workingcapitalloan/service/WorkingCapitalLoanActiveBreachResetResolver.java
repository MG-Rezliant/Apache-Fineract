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
package org.apache.fineract.portfolio.workingcapitalloan.service;

import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.portfolio.workingcapitalloan.domain.WorkingCapitalLoanBreachAction;
import org.apache.fineract.portfolio.workingcapitalloan.domain.WorkingCapitalLoanBreachActionType;
import org.apache.fineract.portfolio.workingcapitalloan.repository.WorkingCapitalLoanBreachActionRepository;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class WorkingCapitalLoanActiveBreachResetResolver {

    private final WorkingCapitalLoanBreachActionRepository breachActionRepository;

    public Optional<WorkingCapitalLoanBreachAction> findLatestActiveReset(final Long workingCapitalLoanId) {
        final Deque<WorkingCapitalLoanBreachAction> queue = new ArrayDeque<>();
        findResetActions(workingCapitalLoanId).forEach(action -> replay(queue, action));
        return Optional.ofNullable(queue.peek());
    }

    /**
     * The reset that the given undo action cancels. The undo row is persisted before the undo is processed, so
     * {@link #findLatestActiveReset} has already popped that reset by the time the undo runs and would return the
     * previous one; the replay here therefore stops at the undo action itself.
     */
    public Optional<WorkingCapitalLoanBreachAction> findResetUndoneBy(final Long workingCapitalLoanId,
            final WorkingCapitalLoanBreachAction undoResetAction) {
        final Deque<WorkingCapitalLoanBreachAction> queue = new ArrayDeque<>();
        for (final WorkingCapitalLoanBreachAction action : findResetActions(workingCapitalLoanId)) {
            if (Objects.equals(action.getId(), undoResetAction.getId())) {
                break;
            }
            replay(queue, action);
        }
        return Optional.ofNullable(queue.peek());
    }

    private List<WorkingCapitalLoanBreachAction> findResetActions(final Long workingCapitalLoanId) {
        return breachActionRepository.findByLoanAndActionType(workingCapitalLoanId,
                List.of(WorkingCapitalLoanBreachActionType.RESET, WorkingCapitalLoanBreachActionType.UNDO_RESET));
    }

    private void replay(final Deque<WorkingCapitalLoanBreachAction> queue, final WorkingCapitalLoanBreachAction action) {
        if (WorkingCapitalLoanBreachActionType.RESET.equals(action.getAction())) {
            queue.push(action);
        } else if (WorkingCapitalLoanBreachActionType.UNDO_RESET.equals(action.getAction()) && !queue.isEmpty()) {
            queue.pop();
        }
    }

    public boolean hasActiveReset(final Long workingCapitalLoanId) {
        return findLatestActiveReset(workingCapitalLoanId).isPresent();
    }

    public boolean existsActiveResetInPeriod(final Long workingCapitalLoanId, final LocalDate fromDate, final LocalDate toDate) {
        return findLatestActiveReset(workingCapitalLoanId).filter(a -> DateUtils.isDateInRangeInclusive(a.getStartDate(), fromDate, toDate))
                .isPresent();
    }
}
