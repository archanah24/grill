/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.lens.server.query;

import static javax.ws.rs.core.Response.Status.BAD_REQUEST;

import static org.apache.lens.api.error.LensCommonErrorCode.INTERNAL_SERVER_ERROR;
import static org.apache.lens.cube.error.LensCubeErrorCode.COLUMN_UNAVAILABLE_IN_TIME_RANGE;
import static org.apache.lens.cube.error.LensCubeErrorCode.SYNTAX_ERROR;
import static org.apache.lens.server.common.RestAPITestUtil.*;
import static org.apache.lens.server.common.TestDataUtils.*;
import static org.apache.lens.server.error.LensServerErrorCode.*;

import java.util.Arrays;

import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.Application;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;
import javax.xml.datatype.DatatypeConfigurationException;

import org.apache.lens.api.LensConf;
import org.apache.lens.api.LensSessionHandle;
import org.apache.lens.api.jaxb.LensJAXBContextResolver;
import org.apache.lens.api.metastore.*;
import org.apache.lens.api.query.SupportedQuerySubmitOperations;
import org.apache.lens.api.result.LensErrorTO;
import org.apache.lens.cube.error.ColUnAvailableInTimeRange;
import org.apache.lens.server.LensJerseyTest;
import org.apache.lens.server.LensRequestContextInitFilter;
import org.apache.lens.server.common.ErrorResponseExpectedData;
import org.apache.lens.server.error.LensExceptionMapper;
import org.apache.lens.server.error.LensJAXBValidationExceptionMapper;
import org.apache.lens.server.metastore.MetastoreResource;
import org.apache.lens.server.session.SessionResource;

import org.glassfish.jersey.client.ClientConfig;
import org.glassfish.jersey.media.multipart.MultiPartFeature;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.test.TestProperties;
import org.glassfish.jersey.test.inmemory.InMemoryTestContainerFactory;
import org.glassfish.jersey.test.spi.TestContainerFactory;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;
import org.testng.annotations.AfterTest;
import org.testng.annotations.BeforeTest;
import org.testng.annotations.Test;

import com.google.common.base.Optional;
import lombok.NonNull;

@Test(groups = "unit-test")
public class QueryAPIErrorResponseTest extends LensJerseyTest {

  private static final String MOCK_QUERY = "mock-query";
  private static final String INVALID_OPERATION = "invalid-operation";

  @BeforeTest
  public void setUp() throws Exception {
    super.setUp();
  }

  @AfterTest
  public void tearDown() throws Exception {
    super.tearDown();
  }

  @Override
  protected Application configure() {

    enable(TestProperties.LOG_TRAFFIC);
    enable(TestProperties.DUMP_ENTITY);

    return new ResourceConfig(LensRequestContextInitFilter.class, SessionResource.class, MetastoreResource.class,
      QueryServiceResource.class, MultiPartFeature.class, LensExceptionMapper.class, LensJAXBContextResolver.class,
      LensRequestContextInitFilter.class, LensJAXBValidationExceptionMapper.class);
  }

  @Override
  protected void configureClient(ClientConfig config) {
    config.register(MultiPartFeature.class);
    config.register(LensJAXBContextResolver.class);
  }

  @Override
  protected TestContainerFactory getTestContainerFactory() {
    return new InMemoryTestContainerFactory();
  }

  @Test
  public void testErrorResponseWhenSessionIdIsAbsent() {

    Response response = estimate(target(), Optional.<LensSessionHandle>absent(), Optional.of(MOCK_QUERY));

    final String expectedErrMsg = "Session id not provided. Please provide a session id.";
    LensErrorTO expectedLensErrorTO = LensErrorTO.composedOf(
        SESSION_ID_NOT_PROVIDED.getLensErrorInfo().getErrorCode(), expectedErrMsg, MOCK_STACK_TRACE);
    ErrorResponseExpectedData expectedData = new ErrorResponseExpectedData(BAD_REQUEST, expectedLensErrorTO);

    expectedData.verify(response);
  }

  @Test
  public void testErrorResponseWhenQueryIsAbsent() {

    LensSessionHandle sessionId = openSession(target(), "foo", "bar", new LensConf());
    Optional<String> testQuery = Optional.absent();
    Response response = estimate(target(), Optional.of(sessionId), testQuery);

    final String expectedErrMsg = "Query is not provided, or it is empty or blank. Please provide a valid query.";
    LensErrorTO expectedLensErrorTO = LensErrorTO.composedOf(
        NULL_OR_EMPTY_OR_BLANK_QUERY.getLensErrorInfo().getErrorCode(), expectedErrMsg, MOCK_STACK_TRACE);
    ErrorResponseExpectedData expectedData = new ErrorResponseExpectedData(BAD_REQUEST, expectedLensErrorTO);

    expectedData.verify(response);
  }

  @Test
  public void testErrorResponseWhenInvalidOperationIsSubmitted() {

    LensSessionHandle sessionId = openSession(target(), "foo", "bar", new LensConf());

    Response response = postQuery(target(), Optional.of(sessionId), Optional.of(MOCK_QUERY),
        Optional.of(INVALID_OPERATION));

    final String expectedErrMsg = "Provided Operation is not supported. Supported Operations are: "
      + "[estimate, execute, explain, execute_with_timeout]";

    LensErrorTO expectedLensErrorTO = LensErrorTO.composedOf(
        UNSUPPORTED_QUERY_SUBMIT_OPERATION.getLensErrorInfo().getErrorCode(),
      expectedErrMsg, MOCK_STACK_TRACE, new SupportedQuerySubmitOperations());
    ErrorResponseExpectedData expectedData = new ErrorResponseExpectedData(BAD_REQUEST, expectedLensErrorTO);

    expectedData.verify(response);
  }

  @Test
  public void testErrorResponseWhenLensMultiCauseExceptionOccurs() {

    LensSessionHandle sessionId = openSession(target(), "foo", "bar");

    final String testQuery = "select * from non_existing_table";
    Response response = estimate(target(), Optional.of(sessionId), Optional.of(testQuery));

    final String expectedErrMsg = "Internal Server Error.";

    LensErrorTO childError1 = LensErrorTO.composedOf(INTERNAL_SERVER_ERROR.getValue(),
      expectedErrMsg, MOCK_STACK_TRACE);
    LensErrorTO childError2 = LensErrorTO.composedOf(INTERNAL_SERVER_ERROR.getValue(),
        expectedErrMsg, MOCK_STACK_TRACE);

    LensErrorTO expectedLensErrorTO = LensErrorTO.composedOf(INTERNAL_SERVER_ERROR.getValue(),
        expectedErrMsg, MOCK_STACK_TRACE, Arrays.asList(childError1, childError2));

    ErrorResponseExpectedData expectedData = new ErrorResponseExpectedData(Status.INTERNAL_SERVER_ERROR,
      expectedLensErrorTO);

    expectedData.verify(response);
  }

  @Test
  public void testErrorResponseWithSyntaxErrorInQuery() {

    LensSessionHandle sessionId = openSession(target(), "foo", "bar", new LensConf());

    Response response = estimate(target(), Optional.of(sessionId), Optional.of(MOCK_QUERY));

    final String expectedErrMsg = "Syntax Error: line 1:0 cannot recognize input near 'mock' '-' 'query'";
    LensErrorTO expectedLensErrorTO = LensErrorTO.composedOf(SYNTAX_ERROR.getLensErrorInfo().getErrorCode(),
      expectedErrMsg, MOCK_STACK_TRACE);
    ErrorResponseExpectedData expectedData = new ErrorResponseExpectedData(BAD_REQUEST, expectedLensErrorTO);

    expectedData.verify(response);
  }

  @Test
  public void testQueryColumnWithBothStartDateAndEndDate() throws DatatypeConfigurationException {

    /* This test will have a col which has both start date and end date set */
    /* Col will be queried for a time range which does not fall in start date and end date */

    DateTime startDateOneJan2015 = new DateTime(2015, 01, 01, 0, 0, DateTimeZone.UTC);
    DateTime endDateThirtyJan2015 = new DateTime(2015, 01, 30, 23, 0, DateTimeZone.UTC);

    DateTime queryFromOneJan2014 = new DateTime(2014, 01, 01, 0, 0, DateTimeZone.UTC);
    DateTime queryTillThreeJan2014 = new DateTime(2014, 01, 03, 0, 0, DateTimeZone.UTC);

    final String expectedErrMsgSuffix = " can only be queried after Thursday, January 1, 2015 12:00:00 AM UTC and "
      + "before Friday, January 30, 2015 11:00:00 PM UTC. Please adjust the selected time range accordingly.";

    testColUnAvailableInTimeRange(Optional.of(startDateOneJan2015),
      Optional.of(endDateThirtyJan2015), queryFromOneJan2014, queryTillThreeJan2014, expectedErrMsgSuffix);
  }

  @Test
  public void testQueryColumnWithOnlyStartDate() throws DatatypeConfigurationException {

    /* This test will have a col which has only start date set */
    /* Col will be queried for a time range which is before start date */

    DateTime startDateOneJan2015 = new DateTime(2015, 01, 01, 0, 0, DateTimeZone.UTC);

    DateTime queryFromOneJan2014 = new DateTime(2014, 01, 01, 0, 0, DateTimeZone.UTC);
    DateTime queryTillThreeJan2014 = new DateTime(2014, 01, 03, 0, 0, DateTimeZone.UTC);

    final String expectedErrMsgSuffix = " can only be queried after Thursday, January 1, 2015 12:00:00 AM UTC. "
      + "Please adjust the selected time range accordingly.";

    testColUnAvailableInTimeRange(Optional.of(startDateOneJan2015),
      Optional.<DateTime>absent(), queryFromOneJan2014, queryTillThreeJan2014, expectedErrMsgSuffix);
  }

  @Test
  public void testQueryColumnWithOnlyEndDate() throws DatatypeConfigurationException {

    /* This test will have a col which has only end date set */
    /* Col will be queried for a time range which is after end date */

    DateTime endDateThirtyJan2015 = new DateTime(2015, 01, 30, 23, 0, DateTimeZone.UTC);

    DateTime queryFromOneJan2016 = new DateTime(2016, 01, 01, 0, 0, DateTimeZone.UTC);
    DateTime queryTillThreeJan2016 = new DateTime(2016, 01, 03, 0, 0, DateTimeZone.UTC);

    final String expectedErrMsgSuffix = " can only be queried before Friday, January 30, 2015 11:00:00 PM UTC. "
      + "Please adjust the selected time range accordingly.";

    testColUnAvailableInTimeRange(Optional.<DateTime>absent(),
      Optional.of(endDateThirtyJan2015), queryFromOneJan2016, queryTillThreeJan2016, expectedErrMsgSuffix);
  }

  private void testColUnAvailableInTimeRange(@NonNull final Optional<DateTime> colStartDate,
    @NonNull final Optional<DateTime> colEndDate, @NonNull DateTime queryFrom, @NonNull DateTime queryTill,
    @NonNull final String expectedErrorMsgSuffix) throws DatatypeConfigurationException {

    final WebTarget target = target();
    final String testDb = getRandomDbName();
    final String testCube = getRandomCubeName();
    final String testDimensionField = getRandomDimensionField();
    final String testFact = getRandomFactName();

    /* Setup: Begin */
    LensSessionHandle sessionId = openSession(target, "foo", "bar", new LensConf());

    try {

      createAndSetCurrentDbFailFast(target, sessionId, testDb);

      /* Create a test cube with test dimension field having a start Date and end Date */
      XDimAttribute testXDim = createXDimAttribute(testDimensionField, colStartDate, colEndDate);
      XCube xcube = createXCubeWithDummyMeasure(testCube, Optional.of("dt"), testXDim);
      createCubeFailFast(target, sessionId, xcube);

      /* Create a fact with test dimension field */
      XColumn xColumn = createXColumn(testDimensionField);
      XFactTable xFactTable = createXFactTableWithColumns(testFact, testCube, xColumn);
      createFactFailFast(target, sessionId, xFactTable);

      /* Setup: End */

      DateTimeFormatter dtf = DateTimeFormat.forPattern("yyyy-MM-dd-HH");
      final String testQuery = "cube select " + testDimensionField + " from " + testCube + " where TIME_RANGE_IN(dt, "
        + "\"" + dtf.print(queryFrom) + "\",\"" + dtf.print(queryTill) + "\")";

      Response response = estimate(target, Optional.of(sessionId), Optional.of(testQuery));

      final String expectedErrMsg = testDimensionField + expectedErrorMsgSuffix;

      Long expecAvailableFrom = colStartDate.isPresent() ? colStartDate.get().getMillis() : null;
      Long expecAvailableTill = colEndDate.isPresent() ? colEndDate.get().getMillis() : null;

      final ColUnAvailableInTimeRange expectedErrorPayload = new ColUnAvailableInTimeRange(testDimensionField,
        expecAvailableFrom, expecAvailableTill);

      LensErrorTO expectedLensErrorTO = LensErrorTO.composedOf(
          COLUMN_UNAVAILABLE_IN_TIME_RANGE.getLensErrorInfo().getErrorCode(),
          expectedErrMsg, MOCK_STACK_TRACE, expectedErrorPayload, null);
      ErrorResponseExpectedData expectedData = new ErrorResponseExpectedData(BAD_REQUEST, expectedLensErrorTO);

      expectedData.verify(response);
    } finally {
      dropDatabaseFailFast(target, sessionId, testDb);
      closeSessionFailFast(target, sessionId);
    }
  }
}
