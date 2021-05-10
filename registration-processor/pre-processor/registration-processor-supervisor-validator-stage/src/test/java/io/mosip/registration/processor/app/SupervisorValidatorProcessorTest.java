package io.mosip.registration.processor.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.HashMap;

import org.apache.commons.io.IOUtils;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.powermock.core.classloader.annotations.PowerMockIgnore;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;

import io.mosip.registration.processor.core.abstractverticle.MessageDTO;
import io.mosip.registration.processor.core.code.ApiName;
import io.mosip.registration.processor.core.code.EventId;
import io.mosip.registration.processor.core.code.EventName;
import io.mosip.registration.processor.core.code.EventType;
import io.mosip.registration.processor.core.constant.RegistrationType;
import io.mosip.registration.processor.core.exception.ApisResourceAccessException;
import io.mosip.registration.processor.core.exception.AuthSystemException;
import io.mosip.registration.processor.core.exception.PacketManagerException;
import io.mosip.registration.processor.core.http.ResponseWrapper;
import io.mosip.registration.processor.core.packet.dto.RegOsiDto;
import io.mosip.registration.processor.core.spi.restclient.RegistrationProcessorRestClientService;
import io.mosip.registration.processor.core.status.util.StatusUtil;
import io.mosip.registration.processor.core.util.JsonUtil;
import io.mosip.registration.processor.core.util.RegistrationExceptionMapperUtil;
import io.mosip.registration.processor.packet.storage.utils.PriorityBasedPacketManagerService;
import io.mosip.registration.processor.packet.storage.utils.Utilities;
import io.mosip.registration.processor.rest.client.audit.builder.AuditLogRequestBuilder;
import io.mosip.registration.processor.rest.client.audit.dto.AuditResponseDto;
import io.mosip.registration.processor.stages.app.SupervisorValidationProcessor;
import io.mosip.registration.processor.stages.supervisor.SupervisorValidator;
import io.mosip.registration.processor.stages.supervisor.UMCValidator;
import io.mosip.registration.processor.stages.utils.OSIUtils;
import io.mosip.registration.processor.status.dto.InternalRegistrationStatusDto;
import io.mosip.registration.processor.status.dto.RegistrationStatusDto;
import io.mosip.registration.processor.status.service.RegistrationStatusService;

/**
 * The Class SupervisorValidatorProcessorTest.
 */
@RunWith(PowerMockRunner.class)
@PrepareForTest({ JsonUtil.class, IOUtils.class })
@PowerMockIgnore({ "com.sun.org.apache.xerces.*", "javax.xml.*", "org.xml.*", "javax.management.*", "javax.net.ssl.*" })
public class SupervisorValidatorProcessorTest {

	@InjectMocks
	private SupervisorValidationProcessor supervisorValidationProcessor;

	@Mock
	private Utilities utility;

	/** The registration status service. */
	@Mock
	RegistrationStatusService<String, InternalRegistrationStatusDto, RegistrationStatusDto> registrationStatusService;

	/** The audit log request builder. */
	@Mock
	private AuditLogRequestBuilder auditLogRequestBuilder;

	/** The center validator. */
	@Mock
	private SupervisorValidator supervisorValidator;

	@Mock
	private UMCValidator umcValidator;

	/** The dto. */
	MessageDTO dto = new MessageDTO();

	RegOsiDto regOsi;

	private String stageName;

	/** The registration status dto. */
	InternalRegistrationStatusDto registrationStatusDto = new InternalRegistrationStatusDto();

	@Mock
	private PriorityBasedPacketManagerService packetManagerService;

	@Mock
	private OSIUtils osiUtils;

	@Mock
	RegistrationExceptionMapperUtil registrationStatusMapperUtil;

	/**
	 * Sets the up.
	 *
	 * @throws Exception the exception
	 */
	@Before
	public void setUp() throws Exception {

		regOsi = new RegOsiDto();
		regOsi.setIsActive(true);
		regOsi.setLatitude("13.0049");
		regOsi.setLongitude("80.24492");
		regOsi.setMachineId("yyeqy26356");
		regOsi.setPacketCreationDate("2018-11-28T15:34:20.122");
		regOsi.setRegcntrId("12245");
		regOsi.setRegId("2018782130000121112018103016");

		Mockito.when(utility.getDefaultSource(any(), any())).thenReturn("reg-client");
		Mockito.when(osiUtils.getOSIDetailsFromMetaInfo(any())).thenReturn(regOsi);
		Mockito.when(packetManagerService.getMetaInfo(anyString(), anyString(), any())).thenReturn(new HashMap<>());

		@SuppressWarnings("unchecked")
		RegistrationProcessorRestClientService<Object> mockObj = Mockito
				.mock(RegistrationProcessorRestClientService.class);

		Field auditLog = AuditLogRequestBuilder.class.getDeclaredField("registrationProcessorRestService");
		auditLog.setAccessible(true);
		auditLog.set(auditLogRequestBuilder, mockObj);
		ResponseWrapper<AuditResponseDto> responseWrapper = new ResponseWrapper<>();
		Mockito.doReturn(responseWrapper).when(auditLogRequestBuilder).createAuditRequestBuilder(
				"test case description", EventId.RPR_401.toString(), EventName.ADD.toString(),
				EventType.BUSINESS.toString(), "1234testcase", ApiName.AUDIT);

		dto.setRid("123456789");
		dto.setInternalError(false);
		dto.setIsValid(true);
		dto.setReg_type(RegistrationType.NEW);
		stageName = "supervisorValidatorStage";
		registrationStatusDto = new InternalRegistrationStatusDto();
		registrationStatusDto.setRegistrationId("123456789");
		registrationStatusDto.setRegistrationId("reg1234");
		Mockito.when(registrationStatusService.getRegistrationStatus(anyString())).thenReturn(registrationStatusDto);
	}

	/**
	 * Testis valid CMD success.
	 *
	 * @throws Exception the exception
	 */
	@Test
	public void testisValidOperatorSuccess() throws Exception {

		Mockito.when(supervisorValidator.isValidSupervisor(anyString(), any(), any())).thenReturn(Boolean.TRUE);
		Mockito.when(umcValidator.isValidUMCmapping(anyString(), anyString(), anyString(), anyString(), any()))
				.thenReturn(Boolean.TRUE);

		assertTrue(supervisorValidationProcessor.process(dto, stageName).getIsValid());
	}

	/**
	 * IO exception test.
	 *
	 * @throws Exception the exception
	 */
	@Test
	public void IOExceptionTest() throws Exception {

		Mockito.when(supervisorValidator.isValidSupervisor(anyString(), any(), any())).thenThrow(new IOException());
		Mockito.when(umcValidator.isValidUMCmapping(anyString(), anyString(), anyString(), anyString(), any()))
				.thenReturn(Boolean.TRUE);

		assertEquals(false, supervisorValidationProcessor.process(dto, stageName).getIsValid());
	}

	@Test
	public void apiResourceExceptionTest() throws Exception {

		Mockito.when(supervisorValidator.isValidSupervisor(anyString(), any(), any()))
				.thenThrow(new ApisResourceAccessException(""));
		Mockito.when(umcValidator.isValidUMCmapping(anyString(), anyString(), anyString(), anyString(), any()))
				.thenReturn(Boolean.TRUE);

		assertEquals(false, supervisorValidationProcessor.process(dto, stageName).getIsValid());
	}

	@Test
	public void exceptionTest() throws Exception {

		Mockito.when(supervisorValidator.isValidSupervisor(anyString(), any(), any()))
				.thenThrow(new NullPointerException(""));
		Mockito.when(umcValidator.isValidUMCmapping(anyString(), anyString(), anyString(), anyString(), any()))
				.thenReturn(Boolean.TRUE);

		assertEquals(false, supervisorValidationProcessor.process(dto, stageName).getIsValid());
	}

	@Test
	public void aPIResourceServerExceptionTest() throws Exception {
		ApisResourceAccessException apisResourceAccessException = Mockito.mock(ApisResourceAccessException.class);
		HttpServerErrorException httpServerErrorException = new HttpServerErrorException(
				HttpStatus.INTERNAL_SERVER_ERROR, "KER-FSE-004:encrypted data is corrupted or not base64 encoded");
		Mockito.when(apisResourceAccessException.getCause()).thenReturn(httpServerErrorException);

		Mockito.when(supervisorValidator.isValidSupervisor(anyString(), any(), any()))
				.thenThrow(apisResourceAccessException);
		Mockito.when(umcValidator.isValidUMCmapping(anyString(), anyString(), anyString(), anyString(), any()))
				.thenReturn(Boolean.TRUE);

		assertEquals(false, supervisorValidationProcessor.process(dto, stageName).getIsValid());
	}

	@Test
	public void aPIResourceClientExceptionTest() throws Exception {
		ApisResourceAccessException apisResourceAccessException = Mockito.mock(ApisResourceAccessException.class);
		HttpClientErrorException httpClientErrorException = new HttpClientErrorException(
				HttpStatus.INTERNAL_SERVER_ERROR, "KER-FSE-004:encrypted data is corrupted or not base64 encoded");
		Mockito.when(apisResourceAccessException.getCause()).thenReturn(httpClientErrorException);
		Mockito.when(supervisorValidator.isValidSupervisor(anyString(), any(), any()))
				.thenThrow(apisResourceAccessException);
		Mockito.when(umcValidator.isValidUMCmapping(anyString(), anyString(), anyString(), anyString(), any()))
				.thenReturn(Boolean.TRUE);
		assertEquals(false, supervisorValidationProcessor.process(dto, stageName).getIsValid());
	}

	/**
	 * Data access exception test.
	 *
	 * @throws Exception the exception
	 */
	@Test
	public void dataAccessExceptionTest() throws Exception {

		Mockito.when(supervisorValidator.isValidSupervisor(anyString(), any(), any()))
				.thenThrow(new DataAccessException("") {
				});
		Mockito.when(umcValidator.isValidUMCmapping(anyString(), anyString(), anyString(), anyString(), any()))
				.thenReturn(Boolean.TRUE);
		assertEquals(false, supervisorValidationProcessor.process(dto, stageName).getIsValid());
	}

	@Test
	public void testAuthSystemException() throws Exception {

		Mockito.when(supervisorValidator.isValidSupervisor(anyString(), any(), any()))
				.thenThrow(new AuthSystemException(StatusUtil.AUTH_SYSTEM_EXCEPTION.getMessage()));
		Mockito.when(umcValidator.isValidUMCmapping(anyString(), anyString(), anyString(), anyString(), any()))
				.thenReturn(Boolean.TRUE);
		assertEquals(false, supervisorValidationProcessor.process(dto, stageName).getIsValid());
	}

	@Test
	public void packetManagerExceptionTest() throws Exception {

		Mockito.when(supervisorValidator.isValidSupervisor(anyString(), any(), any()))
				.thenThrow(new PacketManagerException("id", "message"));
		Mockito.when(umcValidator.isValidUMCmapping(anyString(), anyString(), anyString(), anyString(), any()))
				.thenReturn(Boolean.TRUE);
		assertEquals(false, supervisorValidationProcessor.process(dto, stageName).getIsValid());
	}
}
