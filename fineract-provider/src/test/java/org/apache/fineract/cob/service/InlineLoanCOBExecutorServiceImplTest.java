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
package org.apache.fineract.cob.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.persistence.OptimisticLockException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.apache.fineract.cob.data.COBIdAndLastClosedBusinessDate;
import org.apache.fineract.cob.domain.AccountLockRepository;
import org.apache.fineract.cob.domain.LoanAccountLock;
import org.apache.fineract.cob.domain.LoanAccountLockRepository;
import org.apache.fineract.cob.domain.LockOwner;
import org.apache.fineract.cob.exceptions.AccountLockCannotBeOverruledException;
import org.apache.fineract.cob.loan.LoanCOBConstant;
import org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.config.FineractProperties;
import org.apache.fineract.infrastructure.core.domain.ActionContext;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.jobs.data.JobParameterDTO;
import org.apache.fineract.infrastructure.jobs.domain.CustomJobParameterRepository;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.useradministration.domain.AppUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.configuration.JobRegistry;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)

public class InlineLoanCOBExecutorServiceImplTest {

    @InjectMocks
    private InlineLoanCOBExecutorServiceImpl testObj;
    @Mock
    private TransactionTemplate transactionTemplate;
    @Mock
    private InlineLoanCOBExecutionDataParser dataParser;
    @Mock
    private RetrieveLoanIdService retrieveIdService;
    @Mock
    private FineractProperties fineractProperties;
    @Mock
    private FineractProperties.FineractQueryProperties fineractQueryProperties;
    @Mock
    private FineractProperties.FineractApiProperties fineractApiProperties;
    @Mock
    private FineractProperties.FineractBodyItemSizeLimitProperties fineractBodyItemSizeLimitProperties;
    @Mock
    private LoanAccountLockRepository loanAccountLockRepository;
    @Mock
    private JobOperator jobOperator;
    @Mock
    private JobRegistry jobRegistry;
    @Mock
    private JobRepository jobRepository;
    @Mock
    private CustomJobParameterRepository customJobParameterRepository;
    @Mock
    private PlatformSecurityContext context;
    @Mock
    private AppUser appUser;

    @AfterEach
    public void tearDown() {
        ThreadLocalContextUtil.reset();
    }

    @Test
    public void shouldExceptionThrownIfLoanIsAlreadyLocked() {
        JsonCommand command = mock(JsonCommand.class);
        COBIdAndLastClosedBusinessDate loan = mock(COBIdAndLastClosedBusinessDate.class);
        ThreadLocalContextUtil.setTenant(new FineractPlatformTenant(1L, "default", "Default", "Asia/Kolkata", null));
        HashMap<BusinessDateType, LocalDate> businessDates = new HashMap<>();
        LocalDate businessDate = LocalDate.now(ZoneId.systemDefault());
        businessDates.put(BusinessDateType.BUSINESS_DATE, businessDate);
        businessDates.put(BusinessDateType.COB_DATE, businessDate.minusDays(1));
        ThreadLocalContextUtil.setBusinessDates(businessDates);

        doThrow(new AccountLockCannotBeOverruledException("")).when(transactionTemplate).executeWithoutResult(any());
        when(fineractProperties.getQuery()).thenReturn(fineractQueryProperties);
        when(fineractProperties.getApi()).thenReturn(fineractApiProperties);
        when(dataParser.parseExecution(any())).thenReturn(List.of(1L));
        when(fineractQueryProperties.getInClauseParameterSizeLimit()).thenReturn(65000);
        when(fineractApiProperties.getBodyItemSizeLimit()).thenReturn(fineractBodyItemSizeLimitProperties);
        when(fineractBodyItemSizeLimitProperties.getInlineLoanCob()).thenReturn(1000);
        when(retrieveIdService.retrieveLoanIdsBehindDateOrNull(any(), anyList())).thenReturn(List.of(loan));
        assertThrows(AccountLockCannotBeOverruledException.class, () -> testObj.executeInlineJob(command, "INLINE_LOAN_COB"));
    }

    @Test
    public void shouldListBePartitioned() {
        JsonCommand command = mock(JsonCommand.class);
        COBIdAndLastClosedBusinessDate loan1 = mock(COBIdAndLastClosedBusinessDate.class);
        COBIdAndLastClosedBusinessDate loan2 = mock(COBIdAndLastClosedBusinessDate.class);
        COBIdAndLastClosedBusinessDate loan3 = mock(COBIdAndLastClosedBusinessDate.class);
        ThreadLocalContextUtil.setTenant(new FineractPlatformTenant(1L, "default", "Default", "Asia/Kolkata", null));
        HashMap<BusinessDateType, LocalDate> businessDates = new HashMap<>();
        LocalDate businessDate = LocalDate.now(ZoneId.systemDefault());
        businessDates.put(BusinessDateType.BUSINESS_DATE, businessDate);
        businessDates.put(BusinessDateType.COB_DATE, businessDate.minusDays(1));
        ThreadLocalContextUtil.setBusinessDates(businessDates);

        doThrow(new AccountLockCannotBeOverruledException("")).when(transactionTemplate).executeWithoutResult(any());
        when(fineractProperties.getQuery()).thenReturn(fineractQueryProperties);
        when(fineractProperties.getApi()).thenReturn(fineractApiProperties);
        when(dataParser.parseExecution(any())).thenReturn(List.of(1L, 2L, 3L));
        when(fineractQueryProperties.getInClauseParameterSizeLimit()).thenReturn(2);
        when(fineractApiProperties.getBodyItemSizeLimit()).thenReturn(fineractBodyItemSizeLimitProperties);
        when(fineractBodyItemSizeLimitProperties.getInlineLoanCob()).thenReturn(1000);
        when(retrieveIdService.retrieveLoanIdsBehindDateOrNull(any(), anyList())).thenReturn(List.of(loan1, loan2, loan3));
        assertThrows(AccountLockCannotBeOverruledException.class, () -> testObj.executeInlineJob(command, "INLINE_LOAN_COB"));
        verify(retrieveIdService, times(2)).retrieveLoanIdsBehindDateOrNull(any(), anyList());
    }

    @Test
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public void shouldRetryFailedCatchUpFromOriginalBusinessDateContext() throws Exception {
        long loanId = 1L;
        LocalDate targetCobDate = LocalDate.of(2026, 8, 25);
        LocalDate firstExecutionDate = targetCobDate.minusDays(2);
        HashMap<BusinessDateType, LocalDate> requestBusinessDates = new HashMap<>();
        requestBusinessDates.put(BusinessDateType.COB_DATE, targetCobDate);
        requestBusinessDates.put(BusinessDateType.BUSINESS_DATE, targetCobDate.plusDays(1));
        HashMap<BusinessDateType, LocalDate> expectedBusinessDates = new HashMap<>(requestBusinessDates);
        ThreadLocalContextUtil.setBusinessDates(requestBusinessDates);
        ThreadLocalContextUtil.setActionContext(ActionContext.DEFAULT);

        COBIdAndLastClosedBusinessDate initialLoanState = mock(COBIdAndLastClosedBusinessDate.class);
        when(initialLoanState.getId()).thenReturn(loanId);
        when(initialLoanState.getLastClosedBusinessDate()).thenReturn(firstExecutionDate.minusDays(1));
        COBIdAndLastClosedBusinessDate retryLoanState = mock(COBIdAndLastClosedBusinessDate.class);
        when(retryLoanState.getId()).thenReturn(loanId);
        when(retryLoanState.getLastClosedBusinessDate()).thenReturn(firstExecutionDate);
        when(retrieveIdService.retrieveLoanIdsBehindDateOrNull(eq(targetCobDate), anyList())).thenReturn(List.of(initialLoanState),
                List.of(retryLoanState));

        when(fineractProperties.getQuery()).thenReturn(fineractQueryProperties);
        when(fineractQueryProperties.getInClauseParameterSizeLimit()).thenReturn(65000);
        FineractProperties.RetryProperties retryProperties = mock(FineractProperties.RetryProperties.class);
        FineractProperties.RetryProperties.InstancesProperties instancesProperties = mock(
                FineractProperties.RetryProperties.InstancesProperties.class);
        FineractProperties.RetryProperties.InstancesProperties.ExecuteCommandProperties executeCommandProperties = mock(
                FineractProperties.RetryProperties.InstancesProperties.ExecuteCommandProperties.class);
        when(fineractProperties.getRetry()).thenReturn(retryProperties);
        when(retryProperties.getInstances()).thenReturn(instancesProperties);
        when(instancesProperties.getExecuteCommand()).thenReturn(executeCommandProperties);
        when(executeCommandProperties.getRetryExceptions()).thenReturn(new Class[] { OptimisticLockException.class });

        doAnswer(invocation -> {
            Consumer<TransactionStatus> callback = invocation.getArgument(0);
            callback.accept(mock(TransactionStatus.class));
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());

        AtomicReference<LoanAccountLock> storedLock = new AtomicReference<>();
        AccountLockRepository<LoanAccountLock> accountLockRepository = loanAccountLockRepository;
        when(accountLockRepository.findById(loanId)).thenAnswer(invocation -> Optional.ofNullable(storedLock.get()));
        when(accountLockRepository.saveAndFlush(any(LoanAccountLock.class))).thenAnswer(invocation -> {
            LoanAccountLock lock = invocation.getArgument(0);
            lock.setVersion(lock.getVersion() == null ? 0L : lock.getVersion() + 1);
            storedLock.set(lock);
            return lock;
        });
        when(loanAccountLockRepository.findAllByLoanIdInAndLockOwner(anyList(), eq(LockOwner.LOAN_INLINE_COB_PROCESSING)))
                .thenAnswer(invocation -> storedLock.get() == null ? List.of() : List.of(storedLock.get()));
        when(context.getAuthenticatedUserIfPresent()).thenReturn(appUser);
        when(appUser.isBypassUser()).thenReturn(false);

        Job inlineJob = mock(Job.class);
        when(inlineJob.getName()).thenReturn(LoanCOBConstant.INLINE_LOAN_COB_JOB_NAME);
        when(jobRegistry.getJob(LoanCOBConstant.INLINE_LOAN_COB_JOB_NAME)).thenReturn(inlineJob);
        AtomicLong customParameterId = new AtomicLong();
        List<LocalDate> executionDates = new ArrayList<>();
        when(customJobParameterRepository.save(any())).thenAnswer(invocation -> {
            Set<JobParameterDTO> parameters = invocation.getArgument(0);
            JobParameterDTO parameter = parameters.iterator().next();
            if (parameter.getParameterName().equals(org.apache.fineract.cob.COBConstant.BUSINESS_DATE_PARAMETER_NAME)) {
                executionDates.add(LocalDate.parse(parameter.getParameterValue()));
            }
            return customParameterId.incrementAndGet();
        });

        OptimisticLockException optimisticLockException = new OptimisticLockException("commit failed");
        JobExecution completedExecution = mock(JobExecution.class);
        when(completedExecution.getStatus()).thenReturn(BatchStatus.COMPLETED);
        JobExecution failedExecution = mock(JobExecution.class);
        when(failedExecution.getStatus()).thenReturn(BatchStatus.FAILED);
        when(failedExecution.getAllFailureExceptions()).thenReturn(List.of(optimisticLockException));
        List<JobExecution> executions = List.of(completedExecution, failedExecution, completedExecution, completedExecution);
        AtomicInteger executionIndex = new AtomicInteger();
        when(jobOperator.start(eq(inlineJob), any())).thenAnswer(invocation -> {
            assertEquals(expectedBusinessDates, ThreadLocalContextUtil.getBusinessDates());
            assertEquals(ActionContext.DEFAULT, ThreadLocalContextUtil.getActionContext());
            HashMap<BusinessDateType, LocalDate> mutatedBusinessDates = ThreadLocalContextUtil.getBusinessDates();
            mutatedBusinessDates.put(BusinessDateType.COB_DATE, executionDates.getLast());
            mutatedBusinessDates.put(BusinessDateType.BUSINESS_DATE, executionDates.getLast().plusDays(1));
            ThreadLocalContextUtil.setActionContext(ActionContext.COB);
            JobExecution execution = executions.get(executionIndex.getAndIncrement());
            if (BatchStatus.COMPLETED.equals(execution.getStatus())) {
                storedLock.set(null); // the real writer deletes this lock in the successful chunk transaction
            }
            return execution;
        });

        OptimisticLockException thrown = assertThrows(OptimisticLockException.class,
                () -> testObj.execute(List.of(loanId), LoanCOBConstant.INLINE_LOAN_COB_JOB_NAME));
        assertSame(optimisticLockException, thrown);
        assertEquals(expectedBusinessDates, ThreadLocalContextUtil.getBusinessDates());
        assertEquals(ActionContext.DEFAULT, ThreadLocalContextUtil.getActionContext());
        assertTrue(storedLock.get().getError().contains("Inline COB execution failed"));
        assertTrue(storedLock.get().getStacktrace().contains("commit failed"));

        testObj.execute(List.of(loanId), LoanCOBConstant.INLINE_LOAN_COB_JOB_NAME);

        assertEquals(List.of(firstExecutionDate, firstExecutionDate.plusDays(1), firstExecutionDate.plusDays(1), targetCobDate),
                executionDates);
        assertEquals(4, executionIndex.get());
        assertEquals(expectedBusinessDates, ThreadLocalContextUtil.getBusinessDates());
        assertEquals(ActionContext.DEFAULT, ThreadLocalContextUtil.getActionContext());
        assertNull(storedLock.get());
    }

    @Test
    public void shouldOldestCloseBusinessDateReturnWithCorrectDate()
            throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        COBIdAndLastClosedBusinessDate loan1 = mock(COBIdAndLastClosedBusinessDate.class);
        COBIdAndLastClosedBusinessDate loan2 = mock(COBIdAndLastClosedBusinessDate.class);
        COBIdAndLastClosedBusinessDate loan3 = mock(COBIdAndLastClosedBusinessDate.class);
        when(loan1.getLastClosedBusinessDate()).thenReturn(null);
        when(loan2.getLastClosedBusinessDate()).thenReturn(LocalDate.of(2023, 1, 10));
        when(loan3.getLastClosedBusinessDate()).thenReturn(LocalDate.of(2023, 1, 11));
        assertEquals(LocalDate.of(2023, 1, 10), getOldestCOBBusinessDate().invoke(testObj, List.of(loan1, loan2, loan3)));
    }

    private Method getOldestCOBBusinessDate() throws NoSuchMethodException {
        Method method = InlineCommonLockableCOBExecutorService.class.getDeclaredMethod("getOldestCOBBusinessDate", List.class);
        method.setAccessible(true);
        return method;
    }
}
