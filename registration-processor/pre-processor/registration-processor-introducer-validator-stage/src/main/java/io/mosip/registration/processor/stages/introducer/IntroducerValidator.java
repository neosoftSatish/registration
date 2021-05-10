package io.mosip.registration.processor.stages.introducer;

import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.spec.InvalidKeySpecException;
import java.util.List;
import java.util.stream.Collectors;

import javax.xml.parsers.ParserConfigurationException;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.xml.sax.SAXException;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.mosip.kernel.biometrics.entities.BIR;
import io.mosip.kernel.biometrics.entities.BiometricRecord;
import io.mosip.kernel.core.bioapi.exception.BiometricException;
import io.mosip.kernel.core.exception.BaseCheckedException;
import io.mosip.kernel.core.logger.spi.Logger;
import io.mosip.registration.processor.core.auth.dto.AuthResponseDTO;
import io.mosip.registration.processor.core.code.RegistrationExceptionTypeCode;
import io.mosip.registration.processor.core.constant.LoggerFileConstant;
import io.mosip.registration.processor.core.constant.MappingJsonConstants;
import io.mosip.registration.processor.core.constant.ProviderStageName;
import io.mosip.registration.processor.core.exception.AuthSystemException;
import io.mosip.registration.processor.core.exception.PacketDecryptionFailureException;
import io.mosip.registration.processor.core.exception.ParentOnHoldException;
import io.mosip.registration.processor.core.exception.RegistrationProcessorCheckedException;
import io.mosip.registration.processor.core.exception.util.PlatformErrorMessages;
import io.mosip.registration.processor.core.logger.RegProcessorLogger;
import io.mosip.registration.processor.core.status.util.StatusUtil;
import io.mosip.registration.processor.core.util.RegistrationExceptionMapperUtil;
import io.mosip.registration.processor.packet.manager.idreposervice.IdRepoService;
import io.mosip.registration.processor.packet.storage.utils.AuthUtil;
import io.mosip.registration.processor.packet.storage.utils.PriorityBasedPacketManagerService;
import io.mosip.registration.processor.packet.storage.utils.Utilities;
import io.mosip.registration.processor.stages.utils.StatusMessage;
import io.mosip.registration.processor.status.code.RegistrationStatusCode;
import io.mosip.registration.processor.status.dto.InternalRegistrationStatusDto;
import io.mosip.registration.processor.status.dto.RegistrationStatusDto;
import io.mosip.registration.processor.status.dto.SyncTypeDto;
import io.mosip.registration.processor.status.service.RegistrationStatusService;

@Service
public class IntroducerValidator {

	private static Logger regProcLogger = RegProcessorLogger.getLogger(IntroducerValidator.class);

	public static final String INDIVIDUAL_TYPE_UIN = "UIN";

	@Autowired
	RegistrationExceptionMapperUtil registrationExceptionMapperUtil;

	@Autowired
	private PriorityBasedPacketManagerService packetManagerService;

	@Autowired
	private IdRepoService idRepoService;

	@Autowired
	RegistrationStatusService<String, InternalRegistrationStatusDto, RegistrationStatusDto> registrationStatusService;

	@Autowired
	ObjectMapper mapper;

	@Autowired
	private AuthUtil authUtil;

	@Autowired
	private Utilities utility;

	@Value("${mosip.kernel.applicant.type.age.limit}")
	private String ageLimit;

	@Value("${registration.processor.applicant.dob.format}")
	private String dobFormat;

	@Value("${registration.processor.validate.introducer}")
	private boolean introducerValidation;

	/**
	 * Checks if is valid introducer.
	 *
	 * @param registrationId        the registration id
	 * @param registrationStatusDto
	 * @return true, if is valid introducer
	 * @throws IOException                                Signals that an I/O
	 *                                                    exception has occurred.
	 * @throws SAXException
	 * @throws ParserConfigurationException
	 * @throws NoSuchAlgorithmException
	 * @throws InvalidKeySpecException
	 * @throws BiometricException
	 * @throws io.mosip.kernel.core.exception.IOException
	 * @throws BaseCheckedException
	 * @throws PacketDecryptionFailureException
	 * @throws RegistrationProcessorCheckedException
	 */
	public boolean isValidIntroducer(String registrationId, InternalRegistrationStatusDto registrationStatusDto)
			throws IOException, InvalidKeySpecException, NoSuchAlgorithmException, CertificateException,
			BaseCheckedException {
		regProcLogger.debug(LoggerFileConstant.SESSIONID.toString(), LoggerFileConstant.REGISTRATIONID.toString(),
				registrationId, "IntroducerValidator::isValidIntroducer()::entry");

		if (registrationStatusDto.getRegistrationType().equalsIgnoreCase(SyncTypeDto.NEW.name())
				|| (registrationStatusDto.getRegistrationType().equalsIgnoreCase(SyncTypeDto.UPDATE.name()))) {
			int age = utility.getApplicantAge(registrationId, registrationStatusDto.getRegistrationType(),
					ProviderStageName.INTRODUCER_VALIDATOR);
			int ageThreshold = Integer.parseInt(ageLimit);
			if (age < ageThreshold) {
				if (!introducerValidation)
					return true;

				String introducerUIN = packetManagerService.getFieldByMappingJsonKey(registrationId,
						MappingJsonConstants.PARENT_OR_GUARDIAN_UIN, registrationStatusDto.getRegistrationType(),
						ProviderStageName.INTRODUCER_VALIDATOR);
				String introducerRID = packetManagerService.getFieldByMappingJsonKey(registrationId,
						MappingJsonConstants.PARENT_OR_GUARDIAN_RID, registrationStatusDto.getRegistrationType(),
						ProviderStageName.INTRODUCER_VALIDATOR);

				if (isValidIntroducer(introducerUIN, introducerRID)) {
					registrationStatusDto.setLatestTransactionStatusCode(registrationExceptionMapperUtil
							.getStatusCode(RegistrationExceptionTypeCode.PARENT_UIN_AND_RID_NOT_IN_PACKET));
					registrationStatusDto.setStatusCode(RegistrationStatusCode.REJECTED.toString());
					regProcLogger.debug(LoggerFileConstant.SESSIONID.toString(),
							LoggerFileConstant.REGISTRATIONID.toString(), registrationId,
							StatusMessage.PARENT_UIN_AND_RID_NOT_IN_PACKET);
					throw new BaseCheckedException(StatusUtil.UIN_RID_NOT_FOUND.getMessage(),
							StatusUtil.UIN_RID_NOT_FOUND.getCode());
				}

				if ((introducerUIN == null || introducerUIN.isEmpty())
						&& validateIntroducerRid(introducerRID, registrationId, registrationStatusDto)) {

					introducerUIN = idRepoService.getUinByRid(introducerRID,
							utility.getGetRegProcessorDemographicIdentity());

					if (introducerUIN == null) {
						registrationStatusDto.setLatestTransactionStatusCode(registrationExceptionMapperUtil
								.getStatusCode(RegistrationExceptionTypeCode.PARENT_UIN_NOT_AVAIALBLE));
						registrationStatusDto.setStatusCode(RegistrationStatusCode.FAILED.toString());
						regProcLogger.debug(LoggerFileConstant.SESSIONID.toString(),
								LoggerFileConstant.REGISTRATIONID.toString(), registrationId,
								StatusMessage.PARENT_UIN_NOT_AVAIALBLE);
						throw new BaseCheckedException(StatusUtil.PARENT_UIN_NOT_FOUND.getMessage(),
								StatusUtil.PARENT_UIN_NOT_FOUND.getCode());
					}

				}
				if (introducerUIN != null && !introducerUIN.isEmpty()) {
					return validateIntroducerBiometric(registrationId, registrationStatusDto, introducerUIN);
				} else {
					throw new BaseCheckedException(StatusUtil.INTRODUCER_AUTHENTICATION_FAILED.getMessage(),
							StatusUtil.INTRODUCER_AUTHENTICATION_FAILED.getCode());
				}
			}

		}
		regProcLogger.debug(LoggerFileConstant.SESSIONID.toString(), LoggerFileConstant.REGISTRATIONID.toString(),
				registrationId, "IntroducerValidator::isValidIntroducer()::exit");

		return true;
	}

	private boolean isValidIntroducer(String introducerUIN, String introducerRID) {
		return ((introducerUIN == null && introducerRID == null) || ((introducerUIN != null && introducerUIN.isEmpty())
				&& (introducerRID != null && introducerRID.isEmpty())));
	}

	/**
	 * Validate introducer rid.
	 *
	 * @param introducerRid         the introducer rid
	 * @param registrationId        the registration id
	 * @param registrationStatusDto
	 * @return true, if successful
	 * @throws BaseCheckedException
	 */
	private boolean validateIntroducerRid(String introducerRid, String registrationId,
			InternalRegistrationStatusDto registrationStatusDto) throws BaseCheckedException {
		InternalRegistrationStatusDto introducerRegistrationStatusDto = registrationStatusService
				.getRegistrationStatus(introducerRid);
		if (introducerRegistrationStatusDto != null) {
			if (introducerRegistrationStatusDto.getStatusCode().equals(RegistrationStatusCode.PROCESSING.toString())) {

				registrationStatusDto.setLatestTransactionStatusCode(registrationExceptionMapperUtil
						.getStatusCode(RegistrationExceptionTypeCode.OSI_FAILED_ON_HOLD_PARENT_PACKET));

				registrationStatusDto.setStatusComment(StatusUtil.PACKET_ON_HOLD.getMessage());
				registrationStatusDto.setSubStatusCode(StatusUtil.PACKET_ON_HOLD.getCode());
				registrationStatusDto.setStatusCode(RegistrationStatusCode.PROCESSING.toString());
				regProcLogger.debug(LoggerFileConstant.SESSIONID.toString(),
						LoggerFileConstant.REGISTRATIONID.toString(), registrationId, StatusMessage.PACKET_IS_ON_HOLD);
				throw new ParentOnHoldException(StatusUtil.PACKET_ON_HOLD.getCode(),
						StatusUtil.PACKET_ON_HOLD.getMessage());

			} else if (introducerRegistrationStatusDto.getStatusCode()
					.equals(RegistrationStatusCode.REJECTED.toString())
					|| introducerRegistrationStatusDto.getStatusCode()
							.equals(RegistrationStatusCode.FAILED.toString())) {

				registrationStatusDto.setLatestTransactionStatusCode(registrationExceptionMapperUtil
						.getStatusCode(RegistrationExceptionTypeCode.OSI_FAILED_REJECTED_PARENT));

				registrationStatusDto.setStatusCode(RegistrationStatusCode.FAILED.toString());
				regProcLogger.debug(LoggerFileConstant.SESSIONID.toString(),
						LoggerFileConstant.REGISTRATIONID.toString(), registrationId,
						StatusMessage.OSI_FAILED_REJECTED_PARENT);

				throw new BaseCheckedException(StatusUtil.CHILD_PACKET_REJECTED.getMessage(),
						StatusUtil.CHILD_PACKET_REJECTED.getCode());
			} else {
				return true;
			}

		} else {
			registrationStatusDto.setLatestTransactionStatusCode(registrationExceptionMapperUtil
					.getStatusCode(RegistrationExceptionTypeCode.OSI_FAILED_ON_HOLD_PARENT_PACKET));

			registrationStatusDto.setStatusComment(StatusUtil.PACKET_IS_ON_HOLD.getMessage());
			registrationStatusDto.setSubStatusCode(StatusUtil.PACKET_IS_ON_HOLD.getCode());
			registrationStatusDto.setStatusCode(RegistrationStatusCode.PROCESSING.toString());
			regProcLogger.debug(LoggerFileConstant.SESSIONID.toString(), LoggerFileConstant.REGISTRATIONID.toString(),
					registrationId, StatusMessage.PACKET_IS_ON_HOLD);
			throw new ParentOnHoldException(StatusUtil.PACKET_ON_HOLD.getCode(),
					StatusUtil.PACKET_ON_HOLD.getMessage());
		}

	}

	private boolean validateIntroducerBiometric(String registrationId,
			InternalRegistrationStatusDto registrationStatusDto, String introducerUIN)
			throws IOException, CertificateException, NoSuchAlgorithmException, BaseCheckedException {
		BiometricRecord biometricRecord = packetManagerService.getBiometricsByMappingJsonKey(registrationId,
				MappingJsonConstants.PARENT_OR_GUARDIAN_BIO, registrationStatusDto.getRegistrationType(),
				ProviderStageName.INTRODUCER_VALIDATOR);
		if (biometricRecord != null && biometricRecord.getSegments() != null) {
			return validateUserBiometric(registrationId, introducerUIN, biometricRecord.getSegments(),
					INDIVIDUAL_TYPE_UIN, registrationStatusDto);
		} else {
			registrationStatusDto.setLatestTransactionStatusCode(registrationExceptionMapperUtil
					.getStatusCode(RegistrationExceptionTypeCode.PARENT_BIOMETRIC_NOT_IN_PACKET));
			registrationStatusDto.setStatusCode(RegistrationStatusCode.FAILED.toString());
			regProcLogger.debug(LoggerFileConstant.SESSIONID.toString(), LoggerFileConstant.REGISTRATIONID.toString(),
					registrationId, StatusMessage.PARENT_BIOMETRIC_NOT_IN_PACKET);
			throw new BaseCheckedException(StatusUtil.PARENT_BIOMETRIC_FILE_NAME_NOT_FOUND.getMessage(),
					StatusUtil.PARENT_BIOMETRIC_FILE_NAME_NOT_FOUND.getCode());
		}
	}

	/**
	 * Validate user.
	 *
	 * @param userId                the userid
	 * @param registrationId        the registration id
	 * @param list                  biometric data as BIR object
	 * @param individualType        user type
	 * @param registrationStatusDto
	 * @return true, if successful
	 * @throws SAXException
	 * @throws ParserConfigurationException
	 * @throws NoSuchAlgorithmException
	 * @throws InvalidKeySpecException
	 * @throws IOException                  Signals that an I/O exception has
	 *                                      occurred.
	 * @throws BaseCheckedException
	 * @throws BiometricException
	 */

	private boolean validateUserBiometric(String registrationId, String userId, List<BIR> list, String individualType,
			InternalRegistrationStatusDto registrationStatusDto)
			throws IOException, CertificateException, NoSuchAlgorithmException, BaseCheckedException {

		AuthResponseDTO authResponseDTO = authUtil.authByIdAuthentication(userId, individualType, list);
		if (authResponseDTO.getErrors() == null || authResponseDTO.getErrors().isEmpty()) {
			if (authResponseDTO.getResponse().isAuthStatus()) {
				return true;
			} else {
				registrationStatusDto.setLatestTransactionStatusCode(
						registrationExceptionMapperUtil.getStatusCode(RegistrationExceptionTypeCode.AUTH_FAILED));
				registrationStatusDto.setStatusCode(RegistrationStatusCode.FAILED.toString());
				throw new BaseCheckedException(StatusUtil.INTRODUCER_AUTHENTICATION_FAILED.getMessage() + userId,
						StatusUtil.INTRODUCER_AUTHENTICATION_FAILED.getCode());
			}

		} else {
			List<io.mosip.registration.processor.core.auth.dto.ErrorDTO> errors = authResponseDTO.getErrors();
			if (errors.stream().anyMatch(error -> error.getErrorCode().equalsIgnoreCase("IDA-MLC-007"))) {
				throw new AuthSystemException(PlatformErrorMessages.RPR_AUTH_SYSTEM_EXCEPTION.getMessage());
			} else {
				registrationStatusDto.setLatestTransactionStatusCode(
						registrationExceptionMapperUtil.getStatusCode(RegistrationExceptionTypeCode.AUTH_ERROR));
				registrationStatusDto.setStatusCode(RegistrationStatusCode.FAILED.toString());
				String result = errors.stream().map(s -> s.getErrorMessage() + " ").collect(Collectors.joining());
				regProcLogger.debug(LoggerFileConstant.SESSIONID.toString(),
						LoggerFileConstant.REGISTRATIONID.toString(), registrationId, result);
				throw new BaseCheckedException(result, StatusUtil.INTRODUCER_AUTHENTICATION_FAILED.getCode());
			}

		}

	}

}
