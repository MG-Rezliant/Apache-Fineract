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
package org.apache.fineract.infrastructure.campaigns.email.data;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.exception.InvalidJsonException;
import org.apache.fineract.infrastructure.core.exception.UnsupportedParameterException;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EmailDataValidatorTest {

    private EmailDataValidator validator;

    @BeforeEach
    void setUp() {
        FromJsonHelper fromApiJsonHelper = new FromJsonHelper();
        validator = new EmailDataValidator(fromApiJsonHelper);
    }

    @Test
    void validateCreateRequest_withGroupIdClientIdStaffId_doesNotThrow() {
        // These are the fields EmailMessageAssembler actually reads (EmailApiConstants.CREATE_REQUEST_DATA_PARAMETERS).
        // Previously EmailDataValidator checked against ScheduledEmailConstants.CREATE_REQUEST_PARAMETERS instead,
        // which does not contain groupId/clientId/staffId at all, so this request was wrongly rejected.
        String json = """
                {
                  "groupId": 1,
                  "clientId": 2,
                  "staffId": 3,
                  "emailMessage": "test message"
                }
                """;
        JsonCommand command = JsonCommand.from(json);
        assertDoesNotThrow(() -> validator.validateCreateRequest(command));
    }

    @Test
    void validateCreateRequest_withUnsupportedParameter_stillThrows() {
        String json = """
                {
                  "groupId": 1,
                  "emailMessage": "test message",
                  "notARealParameter": "should be rejected"
                }
                """;
        JsonCommand command = JsonCommand.from(json);
        assertThrows(UnsupportedParameterException.class, () -> validator.validateCreateRequest(command));
    }

    @Test
    void validateCreateRequest_withBlankJson_throwsInvalidJsonException() {
        JsonCommand command = JsonCommand.from("");
        assertThrows(InvalidJsonException.class, () -> validator.validateCreateRequest(command));
    }

    @Test
    void validateUpdateRequest_withMessage_doesNotThrow() {
        // EmailApiConstants.UPDATE_REQUEST_DATA_PARAMETERS contains only emailMessage.
        String json = """
                {
                  "emailMessage": "updated message"
                }
                """;
        JsonCommand command = JsonCommand.from(json);
        assertDoesNotThrow(() -> validator.validateUpdateRequest(command));
    }

    @Test
    void validateUpdateRequest_withClientId_stillThrows() {
        // Pre-existing behavior in EmailApiConstants (not changed by this fix): UPDATE_REQUEST_DATA_PARAMETERS
        // only allows emailMessage, so clientId is rejected here even though it's allowed on create.
        // Documented so this isn't mistaken for a regression by a future reader.
        String json = """
                {
                  "clientId": 2,
                  "emailMessage": "updated message"
                }
                """;
        JsonCommand command = JsonCommand.from(json);
        assertThrows(UnsupportedParameterException.class, () -> validator.validateUpdateRequest(command));
    }

    @Test
    void validateUpdateRequest_withBlankJson_throwsInvalidJsonException() {
        JsonCommand command = JsonCommand.from("");
        assertThrows(InvalidJsonException.class, () -> validator.validateUpdateRequest(command));
    }
}
