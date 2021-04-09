package io.mosip.registration.processor.reprocessor.stage;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.apache.commons.lang3.exception.ExceptionUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import io.mosip.kernel.core.exception.IOException;
import io.mosip.kernel.core.logger.spi.Logger;
import io.mosip.kernel.core.util.DateUtils;
import io.mosip.kernel.core.util.JsonUtils;
import io.mosip.registration.processor.core.abstractverticle.MessageDTO;
import io.mosip.registration.processor.core.abstractverticle.MosipEventBus;
import io.mosip.registration.processor.core.abstractverticle.MosipRouter;
import io.mosip.registration.processor.core.abstractverticle.MosipVerticleAPIManager;
import io.mosip.registration.processor.core.code.EventId;
import io.mosip.registration.processor.core.code.EventName;
import io.mosip.registration.processor.core.code.EventType;
import io.mosip.registration.processor.core.code.ModuleName;
import io.mosip.registration.processor.core.common.rest.dto.ErrorDTO;
import io.mosip.registration.processor.core.exception.WorkflowActionException;
import io.mosip.registration.processor.core.exception.WorkflowActionRequestValidationException;
import io.mosip.registration.processor.core.exception.util.PlatformErrorMessages;
import io.mosip.registration.processor.core.exception.util.PlatformSuccessMessages;
import io.mosip.registration.processor.core.logger.LogDescription;
import io.mosip.registration.processor.core.logger.RegProcessorLogger;
import io.mosip.registration.processor.core.workflow.dto.ResponseDTO;
import io.mosip.registration.processor.core.workflow.dto.WorkflowActionDTO;
import io.mosip.registration.processor.core.workflow.dto.WorkflowActionResponseDTO;
import io.mosip.registration.processor.reprocessor.service.WorkflowActionService;
import io.mosip.registration.processor.reprocessor.validator.WorkflowActionRequestValidator;
import io.mosip.registration.processor.rest.client.audit.builder.AuditLogRequestBuilder;
import io.mosip.registration.processor.status.code.RegistrationStatusCode;
import io.mosip.registration.processor.status.dto.InternalRegistrationStatusDto;
import io.mosip.registration.processor.status.dto.RegistrationStatusDto;
import io.mosip.registration.processor.status.service.RegistrationStatusService;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;

public class WorkflowActionApi extends MosipVerticleAPIManager {


	@Value("${vertx.cluster.configuration}")
	private String clusterManagerUrl;

	/** server port number. */
	@Value("${mosip.regproc.workflowaction.server.port}")
	private String port;

	/** worker pool size. */
	@Value("${worker.pool.size}")
	private Integer workerPoolSize;

	@Value("${mosip.regproc.workflowaction.eventbus.port}")
	private String eventBusPort;

	@Value("${mosip.registration.processor.datetime.pattern}")
	private String dateTimePattern;

	@Value("${mosip.regproc.workflow.action.api-id}")
	private String id;

	@Value("${mosip.regproc.workflow.action.version}")
	private String version;
	@Autowired
	MosipRouter router;

	@Autowired
	private WorkflowActionRequestValidator validator;

	@Autowired
	private WorkflowActionService workflowActionService;
	/**
	 * The context path.
	 */
	@Value("${server.servlet.path}")
	private String contextPath;

	private MosipEventBus mosipEventBus = null;

	/** The reg proc logger. */
	private static Logger regProcLogger = RegProcessorLogger.getLogger(WorkflowActionApi.class);

	/** The registration status service. */
	@Autowired
	RegistrationStatusService<String, InternalRegistrationStatusDto, RegistrationStatusDto> registrationStatusService;

	/** The module id. */
	public static String MODULE_ID = PlatformSuccessMessages.RPR_WORKFLOW_ACTION_API_SUCCESS.getCode();

	/** The module name. */
	public static String MODULE_NAME = ModuleName.WORKFLOW_ACTION_API.toString();

	/** The core audit request builder. */
	@Autowired
	AuditLogRequestBuilder auditLogRequestBuilder;

	/**
	 * Deploy verticle.
	 */
	public void deployVerticle() {
		mosipEventBus = this.getEventBus(this, clusterManagerUrl, workerPoolSize);


	}

	@Override
	public void start() {
		router.setRoute(this.postUrl(getVertx(), null, null));
		this.routes(router);
		this.createServer(router.getRouter(), Integer.parseInt(port));
	}

	@Override
	public Integer getEventBusPort() {
		return Integer.parseInt(eventBusPort);
	}

	/**
	 * contains all the routes in this stage
	 *
	 * @param router
	 */
	private void routes(MosipRouter router) {
		router.post(contextPath + "/workflowaction");
		router.handler(this::processURL, this::failure);
	}

	/**
	 * method to process the context received.
	 *
	 * @param ctx the ctx
	 */
	public void processURL(RoutingContext ctx) {

		List<String> workflowIds = null;
		String workflowAction = null;
		boolean isTransactionSuccessful = false;
		LogDescription description = new LogDescription();
		try {
		JsonObject obj = ctx.getBodyAsJson();

			WorkflowActionDTO workflowActionDTO = (WorkflowActionDTO) JsonUtils
					.jsonStringToJavaObject(WorkflowActionDTO.class, obj.toString());
			workflowIds = workflowActionDTO.getRequest().getWorkflowIds();
			regProcLogger.debug("WorkflowActionApi:processURL called for registration ids {}",
					workflowIds);
			validator.validate(workflowActionDTO);
			List<InternalRegistrationStatusDto> internalRegistrationStatusDtos = new ArrayList<InternalRegistrationStatusDto>();
			for (String workflowId : workflowIds) {
				InternalRegistrationStatusDto internalRegistrationStatusDto = registrationStatusService
						.getRegistrationStatus(workflowId);
				String user="";
				if (Objects.nonNull(ctx.user())
						&& Objects.nonNull(ctx.user().principal()))
				 user = ctx.user().principal().getString("username");
				if (internalRegistrationStatusDto == null) {
					description.setMessage(PlatformErrorMessages.RPR_WAA_WORKFLOW_ID_NOT_FOUND.getMessage());
					updateAudit(description, workflowId, isTransactionSuccessful, user);
					logError(workflowIds, workflowAction, PlatformErrorMessages.RPR_WAA_WORKFLOW_ID_NOT_FOUND.getCode(),
							PlatformErrorMessages.RPR_WAA_WORKFLOW_ID_NOT_FOUND.getMessage() + workflowId, null, ctx);
				} else {
					if (!internalRegistrationStatusDto.getStatusCode().equalsIgnoreCase(RegistrationStatusCode.PAUSED.name())) {
						description.setMessage(PlatformErrorMessages.RPR_WAA_NOT_PAUSED.getMessage());
						updateAudit(description, workflowId, isTransactionSuccessful, user);
						logError(workflowIds, workflowAction, PlatformErrorMessages.RPR_WAA_NOT_PAUSED.getCode(),
								PlatformErrorMessages.RPR_WAA_NOT_PAUSED.getMessage() + workflowId, null, ctx);
					}
			   }
				isTransactionSuccessful = true;
				description.setMessage(PlatformErrorMessages.RPR_WAA_VALIDATION_SUCCESS.getMessage());
				updateAudit(description, workflowId, isTransactionSuccessful, user);
				internalRegistrationStatusDtos.add(internalRegistrationStatusDto);
			}
			workflowActionService.processWorkflowAction(internalRegistrationStatusDtos,
					workflowActionDTO.getRequest().getWorkflowAction());

				regProcLogger.info("Process the workflowAction successfully  for workflow ids and workflowaction {} {}",
						workflowIds,
						workflowAction);
			buildResponse(ctx, "Process the workflowIds '" + workflowIds + "' successfully", null);

			regProcLogger.debug("WorkflowActionApi:processURL ended for registration ids {}",
					workflowActionDTO.getRequest().getWorkflowIds());
		} catch (IOException e) {
			logError(workflowIds, workflowAction,
					PlatformErrorMessages.RPR_SYS_IO_EXCEPTION.getCode(),
					PlatformErrorMessages.RPR_SYS_IO_EXCEPTION.getMessage(), e,ctx);
		} catch (WorkflowActionException e) {
			logError(workflowIds, workflowAction, e.getErrorCode(), e.getMessage(), e, ctx);
		} catch(WorkflowActionRequestValidationException e) {
			logError(workflowIds, workflowAction, e.getErrorCode(), e.getMessage(), e, ctx);
		}catch (Exception e) {
			logError(workflowIds, workflowAction,
					PlatformErrorMessages.RPR_WAA_UNKNOWN_EXCEPTION.getCode(),
					PlatformErrorMessages.RPR_WAA_UNKNOWN_EXCEPTION.getMessage(), e, ctx);
		}
	}

	private void logError(List<String> workflowIds, String workflowAction,
			String errorCode, String errorMessage, Exception e, RoutingContext ctx) {
		if (e != null) {
			regProcLogger.error(
					"Error in  WorkflowActionApi:processURL  for registration ids  and workflowAction {} {} {} {} {}",
					workflowIds, workflowAction, errorMessage, e.getMessage(), ExceptionUtils.getStackTrace(e));
		}

		List<ErrorDTO> errors = new ArrayList<ErrorDTO>();
		ErrorDTO errorDTO = new ErrorDTO();
		errorDTO.setErrorCode(errorCode);
		errorDTO.setMessage(errorMessage);
		errors.add(errorDTO);
		buildResponse(ctx, null, errors);
	}

	private void failure(RoutingContext routingContext) {
		this.setResponse(routingContext, routingContext.failure().getMessage());
	}
	@Override
	public MessageDTO process(MessageDTO object) {
		return null;
	}

	private void buildResponse(RoutingContext routingContext, String message, List<ErrorDTO> errors) {
		WorkflowActionResponseDTO workflowActionResponseDTO = new WorkflowActionResponseDTO();
		workflowActionResponseDTO.setId(id);
		workflowActionResponseDTO.setVersion(version);
		workflowActionResponseDTO.setResponsetime(DateUtils.getUTCCurrentDateTimeString(dateTimePattern));
		if (message == null) {
			workflowActionResponseDTO.setErrors(errors);
		} else {
			ResponseDTO responseDTO = new ResponseDTO();
			responseDTO.setStatusMessage(message);
			workflowActionResponseDTO.setResponse(responseDTO);
		}
		this.setResponse(routingContext, workflowActionResponseDTO);

	}

	/**
	 * Update audit.
	 *
	 * @param description             the description
	 * @param registrationId          the registration id
	 * @param isTransactionSuccessful the is transaction successful
	 */
	private void updateAudit(LogDescription description, String registrationId, boolean isTransactionSuccessful,
			String user) {

		String moduleId = isTransactionSuccessful ? MODULE_ID : description.getCode();

		String eventId = isTransactionSuccessful ? EventId.RPR_402.toString() : EventId.RPR_405.toString();
		String eventName = isTransactionSuccessful ? EventName.UPDATE.toString() : EventName.EXCEPTION.toString();
		String eventType = isTransactionSuccessful ? EventType.BUSINESS.toString() : EventType.SYSTEM.toString();

		auditLogRequestBuilder.createAuditRequestBuilder(description.getMessage() + "user " + "" + user, eventId,
				eventName, eventType,
				moduleId, MODULE_NAME, registrationId);
	}

}
