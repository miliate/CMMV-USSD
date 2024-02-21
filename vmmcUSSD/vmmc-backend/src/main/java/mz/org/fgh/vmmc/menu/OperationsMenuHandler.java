package mz.org.fgh.vmmc.menu;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.apache.log4j.Logger;

import mz.org.fgh.vmmc.client.RestClient;
import mz.org.fgh.vmmc.commons.LocationType;
import mz.org.fgh.vmmc.inout.AppointmentRequest;
import mz.org.fgh.vmmc.inout.AppointmentResponse;
import mz.org.fgh.vmmc.inout.AppointmentSearchResponse;
import mz.org.fgh.vmmc.inout.PayloadSms;
import mz.org.fgh.vmmc.inout.RecipientSms;
import mz.org.fgh.vmmc.inout.SendSmsRequest;
import mz.org.fgh.vmmc.inout.UssdRequest;
import mz.org.fgh.vmmc.inout.UtenteSearchResponse;
import mz.org.fgh.vmmc.model.Clinic;
import mz.org.fgh.vmmc.model.CurrentState;
import mz.org.fgh.vmmc.model.District;
import mz.org.fgh.vmmc.model.InfoMessage;
import mz.org.fgh.vmmc.model.Menu;
import mz.org.fgh.vmmc.model.OperationMetadata;
import mz.org.fgh.vmmc.model.Province;
import mz.org.fgh.vmmc.model.SessionData;
import mz.org.fgh.vmmc.service.FrontlineSmsConfigService;
import mz.org.fgh.vmmc.service.InfoMessageService;
import mz.org.fgh.vmmc.service.MenuService;
import mz.org.fgh.vmmc.service.OperationMetadataService;
import mz.org.fgh.vmmc.service.SessionDataService;
import mz.org.fgh.vmmc.utils.ConstantUtils;
import mz.org.fgh.vmmc.utils.DateUtils;
import mz.org.fgh.vmmc.utils.MenuUtils;
import mz.org.fgh.vmmc.utils.MessageUtils;

public class OperationsMenuHandler implements MenuHandler {

	Logger LOG = Logger.getLogger(OperationsMenuHandler.class);
	private Map<String, Clinic> mapClinics;
	private List<Clinic> clinicsList = new ArrayList<Clinic>();
	private AppointmentRequest appointmentRequest;
	private int lastIndex;
	private int startIndex;
	private final int pagingSize = 3;
	private Map<String, Province> mapProvinces;
	private Map<String, District> mapDistricts;
	private List<District> districtList;
	private List<Province> allProvinces;
	private static OperationsMenuHandler instance = new OperationsMenuHandler();

	private OperationsMenuHandler() {
	}

	public static OperationsMenuHandler getInstance() {
		return instance;
	}

	@Override
	public String handleMenu(UssdRequest ussdRequest, CurrentState currentState, MenuService menuService,
			OperationMetadataService operationMetadataService, SessionDataService sessionDataService,
			InfoMessageService infoMessageService, FrontlineSmsConfigService frontlineSmsConfigService)
			throws Throwable {

		Menu currentMenu = menuService.getCurrentMenuBySessionId(currentState.getSessionId(), true);

		if (currentMenu != null) {
			// Invoca o servico de consulta
			if (currentMenu.getCode().equalsIgnoreCase(ConstantUtils.MENU_APPOINTMENT_CONFIRMATION_CODE) || currentMenu
					.getCode().equalsIgnoreCase(ConstantUtils.MENU_APPOINTMENT_CONFIRMATION_RESCHEDULE_CODE)) {
				return handleRegisterConfirmation(currentMenu, ussdRequest, currentState, menuService,
						operationMetadataService, sessionDataService, infoMessageService, frontlineSmsConfigService);
			}

			else if (!ussdRequest.getText().equals(ConstantUtils.OPTION_VOLTAR)
					|| ussdRequest.getText().equals(ConstantUtils.OPTION_VER_MAIS)) {

				if (currentMenu.getCode().equalsIgnoreCase(ConstantUtils.MENU_PROVINCES_RESCHEDULE_CODE)
						|| currentMenu.getCode().equalsIgnoreCase(ConstantUtils.MENU_PROVINCES_US_LIST_CODE)) {
					return handleMenuProvince(currentMenu, ussdRequest, currentState, menuService,
							operationMetadataService, sessionDataService, infoMessageService,
							frontlineSmsConfigService);
				} else if (currentMenu.getCode().equalsIgnoreCase(ConstantUtils.MENU_DISTRICTS_RESCHEDULE_CODE)
						|| currentMenu.getCode().equalsIgnoreCase(ConstantUtils.MENU_DISTRICTS_US_LIST_CODE)) {

					return handleMenuDistrict(currentMenu, ussdRequest, currentState, menuService,
							operationMetadataService, sessionDataService, infoMessageService,
							frontlineSmsConfigService);
				} else if (currentMenu.getCode().equalsIgnoreCase(ConstantUtils.MENU_AUTHENTICATION_CODE)) {
					return autenticate(currentMenu, ussdRequest, currentState, menuService, sessionDataService);
				} else if (ConstantUtils.MENU_CLINICS_LIST_APPOINTMENT_CODE.equalsIgnoreCase(currentMenu.getCode())
						|| ConstantUtils.MENU_CLINICS_LIST_CODE.equalsIgnoreCase(currentMenu.getCode())
						|| ConstantUtils.MENU_CLINICS_LIST_APPOINTMENT_RESCHEDULE_CODE
								.equalsIgnoreCase(currentMenu.getCode())) {
					if (ussdRequest.getText().equalsIgnoreCase(ConstantUtils.OPTION_VER_MAIS)) {
						return getClinicsByDistrictMenu(ussdRequest, currentState, sessionDataService, currentMenu);

					} else if ("00".equalsIgnoreCase(ussdRequest.getText())
							&& ConstantUtils.MENU_CLINICS_LIST_CODE.equalsIgnoreCase(currentMenu.getCode())) {

						return navegate(currentMenu, ussdRequest, currentState, menuService, operationMetadataService,
								sessionDataService, infoMessageService, frontlineSmsConfigService);

					} else if (mapClinics.containsKey(ussdRequest.getText())) {
						// Seta o ID da clinica correspondente a opcao escolhida
						Clinic clinica = mapClinics.get(ussdRequest.getText());
						sessionDataService.saveClinicOnSessionData(clinica, currentState.getId());
						ussdRequest.setText(clinica.getId() + "");
						lastIndex = pagingSize;
						startIndex = 0;

					} else {
						return MessageFormat.format(ConstantUtils.MESSAGE_OPCAO_INVALIDA, StringUtils.remove(
								getClinicsByDistrictMenu(ussdRequest, currentState, sessionDataService, currentMenu),
								"CON "));
					}
				} else if (ussdRequest.getText().equalsIgnoreCase("1")
						&& ConstantUtils.MENU_CONFIRMATION_SMS_CLINICS_LIST_CODE
								.equalsIgnoreCase(currentMenu.getCode())) {
					// FrontlineSmsConfig configsSms =
					// frontlineSmsConfigService.findFrontlineSmsConfigByCode(ConstantUtils.FRONTLINE_SMS_CONFIG).get(0);

					long districtId = Long.parseLong(sessionDataService
							.findByCurrentStateIdAndAttrName(currentState.getId(), "districtId").getAttrValue());
					List<Clinic> clinics = RestClient.getInstance().getClinicsByDistrict(districtId).getClinics()
							.stream().sorted(Comparator.comparing(Clinic::getName)).collect(Collectors.toList());

					currentState.setActive(false);
					menuService.saveCurrentState(currentState);
					RecipientSms[] recipients = new RecipientSms[1];
					recipients[0] = new RecipientSms(ConstantUtils.TYPE_RECIEPIENT_MOBILE,
							ussdRequest.getPhoneNumber());
					PayloadSms payload = new PayloadSms(getClinicsForSms(clinics), recipients);
					SendSmsRequest smsRequest = new SendSmsRequest();
					smsRequest.setPayload(payload);
					// RestClient.getInstance().sendSms(smsRequest,configsSms);

					return ConstantUtils.MESSAGE_SEND_SMS_CLINIC_LIST;

				} else if (ConstantUtils.MENU_APPOINTMENT_MONTH.equalsIgnoreCase(currentMenu.getCode())
						|| ConstantUtils.MENU_APPOINTMENT_RESCHEDULE_MONTH.equalsIgnoreCase(currentMenu.getCode())) {

					String month = ussdRequest.getText();
					if (!DateUtils.isValidMonth(month)) {
						return MessageFormat.format(ConstantUtils.MESSAGE_OPCAO_INVALIDA,
								StringUtils.remove(MessageFormat.format(MessageUtils.getMenuText(currentMenu),
										DateUtils.getAppointmentsMonth()), "CON"));
					}

				} else if (ConstantUtils.MENU_APPOINTMENT_ON_REGISTRATION_MONTH
						.equalsIgnoreCase(currentMenu.getCode())) {

					String month = ussdRequest.getText();
					if (!DateUtils.isValidMonth(month)) {
						return MessageFormat.format(ConstantUtils.MESSAGE_OPCAO_INVALIDA,
								StringUtils.remove(MessageFormat.format(MessageUtils.getMenuText(currentMenu),
										DateUtils.getAppointmentsMonth()), "CON"));
					}

				} else if (ConstantUtils.MENU_APPOINTMENT_DAY.equalsIgnoreCase(currentMenu.getCode())
						|| ConstantUtils.MENU_APPOINTMENT_RESCHEDULE_DAY.equalsIgnoreCase(currentMenu.getCode())) {

					String keyMonth = ConstantUtils.MENU_APPOINTMENT_DAY.equalsIgnoreCase(currentMenu.getCode())
							? "appointmentMonth"
							: "monthReschedule";

					String month = operationMetadataService
							.getMetadatasByOperationTypeAndSessionId(currentState.getId(), currentState.getLocation())
							.get(keyMonth).getAttrValue();

					if (!DateUtils.isValidDay(ussdRequest.getText(), month)) {
						return MessageFormat.format(ConstantUtils.MESSAGE_APPOINTMENT_DAY_INVALID,
								StringUtils.remove(MessageUtils.getMenuText(currentMenu), "CON "));
					}

				} else if (ConstantUtils.MENU_APPOINTMENT_DETAILS_CODE.equalsIgnoreCase(currentMenu.getCode())) {

					if ((ussdRequest.getText().equalsIgnoreCase("1") || ussdRequest.getText().equalsIgnoreCase("2"))) {
						String utenteId = sessionDataService
								.findByCurrentStateIdAndAttrName(currentState.getId(), "utenteId").getAttrValue();
						AppointmentSearchResponse appointment = RestClient.getInstance()
								.getAppointmentByUtenteId(utenteId);
						if (appointment.getStatus().equalsIgnoreCase("CONFIRMADO")) {
							MenuUtils.resetSession(currentState, menuService);
							return MessageFormat.format(ConstantUtils.MESSAGE_UPDATE_APPOINTMENT_ERROR,
									StringUtils.remove(MessageUtils.getMenuText(currentMenu), "CON "));
						}
					} else {
						return MessageFormat.format(ConstantUtils.MESSAGE_OPCAO_INVALIDA, StringUtils.remove(
								getAppointmentDetails(currentState, menuService, sessionDataService, currentMenu),
								"CON "));
					}
				}

				else if (!MessageUtils.isValidOption(currentMenu, ussdRequest.getText())) {
					// Valida as opcoes para os menus com opcoes predifinidas na Base

					return MessageFormat.format(ConstantUtils.MESSAGE_OPCAO_INVALIDA,
							StringUtils.remove(MessageUtils.getMenuText(currentMenu), "CON "));
				}

				// Grava os dados introduzidos na tabela de metadados (Ex: attrName: age;
				if (StringUtils.isNotBlank(currentMenu.getMenuField())) {
					OperationMetadata metadata = new OperationMetadata(currentState, currentState.getSessionId(),
							currentState.getLocation(), currentMenu, currentMenu.getMenuField(), ussdRequest.getText());

					operationMetadataService.saveOperationMetadata(metadata);
				}

			}

			return navegate(currentMenu, ussdRequest, currentState, menuService, operationMetadataService,
					sessionDataService, infoMessageService, frontlineSmsConfigService);

		} else

		{

			return MainMenuHandler.getInstance().handleMenu(ussdRequest, currentState, menuService,
					operationMetadataService, sessionDataService, infoMessageService, frontlineSmsConfigService);

		}

	}

	// ==============================PRIVATE
	// BEHAVIOUR======================================

	// Responsavel por passar para o proximo menu
	private String navegate(Menu currentMenu, UssdRequest request, CurrentState currentState, MenuService menuService,
			OperationMetadataService operationMetadataService, SessionDataService sessionDataService,
			InfoMessageService infoMessageService, FrontlineSmsConfigService frontlineSmsConfigService) {

		// UtenteSearchResponse utente = (UtenteSearchResponse)
		// httpSession.getAttribute("utenteSession");

		// Passa para o proximo menu, associado a opcao
		//
		// se tiver mais opcoes para seleccionar e não somente a opcao (0. Voltar) OU se
		// tiver apenas a opcao (0. Voltar), pega a opcao
		// introduzida pelo user para saber o proximo menu;
		if ((currentMenu.getMenuItems().size() > 1) || (currentMenu.getMenuItems().size() == 1
				&& StringUtils.trim(request.getText()).equals(ConstantUtils.OPTION_VOLTAR))) {
			// caso particular, se for clinica passa para a proxima tela
			if ((currentMenu.getCode().equalsIgnoreCase(ConstantUtils.MENU_CLINICS_LIST_APPOINTMENT_CODE) || currentMenu
					.getCode().equalsIgnoreCase(ConstantUtils.MENU_CLINICS_LIST_APPOINTMENT_RESCHEDULE_CODE))
					&& !request.getText().equalsIgnoreCase(ConstantUtils.OPTION_VER_MAIS)
					&& !request.getText().equalsIgnoreCase(ConstantUtils.OPTION_VOLTAR)) {
				Menu nextMenu = menuService.findMenuById(currentMenu.getNextMenuId());
				currentState.setIdMenu(nextMenu.getId());
				menuService.saveCurrentState(currentState);
				if (nextMenu.getCode().equalsIgnoreCase(ConstantUtils.MENU_APPOINTMENT_MONTH)
						|| nextMenu.getCode().equalsIgnoreCase(ConstantUtils.MENU_APPOINTMENT_RESCHEDULE_MONTH)) {
					return MessageFormat.format(MessageUtils.getMenuText(nextMenu), DateUtils.getAppointmentsMonth());
				}
				return MessageUtils.getMenuText(nextMenu);
			} else if ((currentMenu.getCode().equalsIgnoreCase(ConstantUtils.MENU_DISTRICTS_RESCHEDULE_CODE)
					|| currentMenu.getCode().equalsIgnoreCase(ConstantUtils.MENU_DISTRICTS_US_LIST_CODE))
					&& !StringUtils.trim(request.getText()).equals(ConstantUtils.OPTION_VOLTAR)
					&& !StringUtils.trim(request.getText()).equals(ConstantUtils.OPTION_VER_MAIS)) {
				Menu nextMenu = menuService.findMenuById(currentMenu.getNextMenuId());
				currentState.setIdMenu(nextMenu.getId());
				menuService.saveCurrentState(currentState);

				if (nextMenu.getCode().equalsIgnoreCase(ConstantUtils.MENU_CLINICS_LIST_APPOINTMENT_RESCHEDULE_CODE)
						|| nextMenu.getCode().equalsIgnoreCase(ConstantUtils.MENU_CLINICS_LIST_CODE)) {
					return getClinicsByDistrictMenu(request, currentState, sessionDataService, nextMenu);
				}
				return MessageUtils.getMenuText(nextMenu);

			}

			// pega o menu pelo opcao introduzida
			Optional<Menu> menu = menuService.getCurrentMenuBySessionId(currentMenu.getId(), request.getText());
			if (!menu.isPresent()) {
				return MessageFormat.format(ConstantUtils.MESSAGE_OPCAO_INVALIDA,
						StringUtils.remove(MessageUtils.getMenuText(currentMenu), "CON "));
			}
			currentState.setIdMenu(menu.get().getNextMenuId());
			menuService.saveCurrentState(currentState);
			Menu nextMenu = menuService.findMenuById(menu.get().getNextMenuId());

			if (nextMenu.getCode().equalsIgnoreCase(ConstantUtils.MENU_PROVINCES_CODE)
					|| nextMenu.getCode().equalsIgnoreCase(ConstantUtils.MENU_PROVINCES_RESCHEDULE_CODE)
					|| nextMenu.getCode().equalsIgnoreCase(ConstantUtils.MENU_PROVINCES_US_LIST_CODE)) {
				return MessageFormat.format(MessageUtils.getMenuText(nextMenu), getProvincesMenu());
			} else if ((currentMenu.getCode().equalsIgnoreCase(ConstantUtils.MENU_DISTRICTS_RESCHEDULE_CODE)
					|| currentMenu.getCode().equalsIgnoreCase(ConstantUtils.MENU_DISTRICTS_US_LIST_CODE))
					&& !StringUtils.trim(request.getText()).equals(ConstantUtils.OPTION_VOLTAR)) {

				if (nextMenu.getCode().equalsIgnoreCase(ConstantUtils.MENU_CLINICS_LIST_APPOINTMENT_RESCHEDULE_CODE)
						|| nextMenu.getCode().equalsIgnoreCase(ConstantUtils.MENU_CLINICS_LIST_CODE)) {
					return getClinicsByDistrictMenu(request, currentState, sessionDataService, nextMenu);
				} else {
					int selectedProvinceId = Integer.parseInt(sessionDataService
							.findByCurrentStateIdAndAttrName(currentState.getId(), "provinceId").getAttrValue());
					return getDistrictsMenu(selectedProvinceId, allProvinces, request, nextMenu);
				}

			} else if (nextMenu.getCode().equalsIgnoreCase(ConstantUtils.MENU_CLINICS_LIST_APPOINTMENT_CODE)
					|| nextMenu.getCode().equalsIgnoreCase(ConstantUtils.MENU_CLINICS_LIST_CODE) || nextMenu.getCode()
							.equalsIgnoreCase(ConstantUtils.MENU_CLINICS_LIST_APPOINTMENT_RESCHEDULE_CODE)) {
				return getClinicsByDistrictMenu(request, currentState, sessionDataService, nextMenu);
			} else if (nextMenu.getCode().equalsIgnoreCase(ConstantUtils.MENU_APPOINTMENT_DETAILS_CODE)) {

				return getAppointmentDetails(currentState, menuService, sessionDataService, nextMenu);

			} else if (nextMenu.getCode().equalsIgnoreCase(ConstantUtils.MENU_APPOINTMENT_MONTH)
					|| ConstantUtils.MENU_APPOINTMENT_RESCHEDULE_MONTH.equalsIgnoreCase(nextMenu.getCode())) {

				return MessageFormat.format(MessageUtils.getMenuText(nextMenu), DateUtils.getAppointmentsMonth());

			} else if (nextMenu.getCode().equalsIgnoreCase(ConstantUtils.MENU_CONFIRMATION_SMS_CLINICS_LIST_CODE)) {

				nextMenu.setDescription(MessageFormat.format(nextMenu.getDescription(), request.getPhoneNumber()));
			} else if (!ConstantUtils.OPTION_VOLTAR.equalsIgnoreCase(request.getText())
					&& ConstantUtils.MENU_INFORMATIVE_MESSAGES.equalsIgnoreCase(currentMenu.getCode())) {
				// FrontlineSmsConfig configsSms =
				// frontlineSmsConfigService.findFrontlineSmsConfigByCode(ConstantUtils.FRONTLINE_SMS_CONFIG).get(0);

				List<InfoMessage> listMessage = infoMessageService.findMessagesByCode(menu.get().getCode());
				for (InfoMessage message : listMessage) {
					RecipientSms[] recipients = new RecipientSms[1];
					recipients[0] = new RecipientSms(ConstantUtils.TYPE_RECIEPIENT_MOBILE, request.getPhoneNumber());
					PayloadSms payload = new PayloadSms(message.getDescription(), recipients);
					SendSmsRequest smsRequest = new SendSmsRequest();
					smsRequest.setPayload(payload);
					// RestClient.getInstance().sendSms(smsRequest,configsSms);
				}
				MenuUtils.resetSession(currentState, menuService);
				return MessageFormat.format(ConstantUtils.MESSAGE_INFORMATIVE_MESSAGES, request.getPhoneNumber());
			}

			return MessageUtils.getMenuText(nextMenu);

		} else {

			currentState.setIdMenu(currentMenu.getNextMenuId());
			menuService.saveCurrentState(currentState);
			// pega o proximo menu
			Menu nextMenu = menuService.findMenuById(currentMenu.getNextMenuId());
			if (nextMenu.getCode().equalsIgnoreCase(ConstantUtils.MENU_APPOINTMENT_CONFIRMATION_CODE) || nextMenu
					.getCode().equalsIgnoreCase(ConstantUtils.MENU_APPOINTMENT_CONFIRMATION_RESCHEDULE_CODE)) {
				return handleMenuConfirmationPage(request, currentState, nextMenu, operationMetadataService,
						menuService, sessionDataService);
			} else if (nextMenu.getCode().equalsIgnoreCase(ConstantUtils.MENU_PROVINCES_RESCHEDULE_CODE)) {
				return MessageFormat.format(MessageUtils.getMenuText(nextMenu), getProvincesMenu());
			} else if (nextMenu.getCode().equalsIgnoreCase(ConstantUtils.MENU_DISTRICTS_RESCHEDULE_CODE)
					|| nextMenu.getCode().equalsIgnoreCase(ConstantUtils.MENU_DISTRICTS_US_LIST_CODE)) {

				int selectedProvinceId = Integer.parseInt(sessionDataService
						.findByCurrentStateIdAndAttrName(currentState.getId(), "provinceId").getAttrValue());
				return getDistrictsMenu(selectedProvinceId, allProvinces, request, nextMenu);
			} else if (currentMenu.getCode().equalsIgnoreCase(ConstantUtils.MENU_CLINICS_LIST_APPOINTMENT_CODE)
					&& !request.getText().equalsIgnoreCase(ConstantUtils.OPTION_VER_MAIS)
					&& !request.getText().equalsIgnoreCase(ConstantUtils.OPTION_VOLTAR)) {
				return MessageUtils.getMenuText(nextMenu);
			} else if (nextMenu.getCode().equalsIgnoreCase(ConstantUtils.MENU_APPOINTMENT_MONTH)
					|| nextMenu.getCode().equalsIgnoreCase(ConstantUtils.MENU_APPOINTMENT_RESCHEDULE_MONTH)) {

				return MessageFormat.format(getNextMenuText(currentState, currentMenu, menuService),
						DateUtils.getAppointmentsMonth());

			} else if (nextMenu.getCode().equalsIgnoreCase(ConstantUtils.MENU_CONFIRMATION_SMS_CLINICS_LIST_CODE)) {

				nextMenu.setDescription(MessageFormat.format(nextMenu.getDescription(), request.getPhoneNumber()));

			}

			return getNextMenuText(currentState, currentMenu, menuService);

		}

	}

	@Override
	public String recoverSession(UssdRequest request, CurrentState currentState, MenuService menuService,
			SessionDataService sessionDataService, OperationMetadataService operationMetadataService) {
		currentState.setSessionId(request.getSessionId());
		menuService.saveCurrentState(currentState);
		Menu menu = menuService.findMenuById(currentState.getIdMenu());
		if (ConstantUtils.MENU_CLINICS_LIST_CODE.equalsIgnoreCase(menu.getCode())
				|| ConstantUtils.MENU_CLINICS_LIST_APPOINTMENT_CODE.equalsIgnoreCase(menu.getCode())
				|| ConstantUtils.MENU_CLINICS_LIST_APPOINTMENT_RESCHEDULE_CODE.equalsIgnoreCase(menu.getCode())) {
			return getClinicsByDistrictMenu(request, currentState, sessionDataService, menu);
		} else if (ConstantUtils.MENU_APPOINTMENT_MONTH.equalsIgnoreCase(menu.getCode())
				|| ConstantUtils.MENU_APPOINTMENT_ON_REGISTRATION_MONTH.equalsIgnoreCase(menu.getCode())) {

			return MessageFormat.format(MessageUtils.getMenuText(menu), DateUtils.getAppointmentsMonth());

		} else if (ConstantUtils.MENU_APPOINTMENT_DETAILS_CODE.equalsIgnoreCase(menu.getCode())) {
			return getAppointmentDetails(currentState, menuService, sessionDataService, menu);
		} else if (ConstantUtils.MENU_CONFIRMATION_SMS_CLINICS_LIST_CODE.equalsIgnoreCase(menu.getCode())) {
			menu.setDescription(MessageFormat.format(menu.getDescription(), request.getPhoneNumber()));
			return MessageUtils.getMenuText(menu);
		} else if (menu.getCode().equalsIgnoreCase(ConstantUtils.MENU_APPOINTMENT_CONFIRMATION_CODE)
				|| menu.getCode().equalsIgnoreCase(ConstantUtils.MENU_APPOINTMENT_CONFIRMATION_RESCHEDULE_CODE)) {
			return handleMenuConfirmationPage(request, currentState, menu, operationMetadataService, menuService,
					sessionDataService);
		} else if (menu.getCode().equalsIgnoreCase(ConstantUtils.MENU_PROVINCES_CODE)
				|| menu.getCode().equalsIgnoreCase(ConstantUtils.MENU_PROVINCES_RESCHEDULE_CODE)
				|| menu.getCode().equalsIgnoreCase(ConstantUtils.MENU_PROVINCES_US_LIST_CODE)) {
			return MessageFormat.format(MessageUtils.getMenuText(menu), getProvincesMenu());
		} else if (menu.getCode().equalsIgnoreCase(ConstantUtils.MENU_DISTRICTS_RESCHEDULE_CODE)
				|| menu.getCode().equalsIgnoreCase(ConstantUtils.MENU_DISTRICTS_US_LIST_CODE)) {
			int selectedProvinceId = Integer.parseInt(sessionDataService
					.findByCurrentStateIdAndAttrName(currentState.getId(), "provinceId").getAttrValue());
			allProvinces = RestClient.getInstance().getAllProvinces();
			return getDistrictsMenu(selectedProvinceId, allProvinces, request, menu);

		}
		return MessageUtils.getMenuText(menu);

	}

	// =================================MENU HANDLERS===========================

	private String handleRegisterConfirmation(Menu currentMenu, UssdRequest ussdRequest, CurrentState currentState,
			MenuService menuService, OperationMetadataService operationMetadataService,
			SessionDataService sessionDataService, InfoMessageService infoMessageService,
			FrontlineSmsConfigService frontlineSmsConfigService) throws Throwable {
		if (ussdRequest.getText().equalsIgnoreCase("1")) {

			AppointmentResponse response = RestClient.getInstance().updateAppointment(appointmentRequest);
			if ((response.getStatusCode() != 200 && response.getStatusCode() != 201)) {
				currentState.setIdMenu(1);
				currentState.setLocation(LocationType.MENU_PRINCIPAL.getCode());
				menuService.saveCurrentState(currentState);
				return ConstantUtils.MESSAGE_APPOINTMENT_FAILED;
			} else {
				currentState.setActive(false);
				menuService.saveCurrentState(currentState);
				return ConstantUtils.MESSAGE_APPOINTMENT_SUCCESS;
			}
		} else if (ussdRequest.getText().equalsIgnoreCase("2")) {

			MenuUtils.resetSession(currentState, menuService);
			return ConstantUtils.MESSAGE_APPOINTMENT_NOT_CONFIRMED;

		} else if (ussdRequest.getText().equalsIgnoreCase(ConstantUtils.OPTION_VOLTAR)) {
			return navegate(currentMenu, ussdRequest, currentState, menuService, operationMetadataService,
					sessionDataService, infoMessageService, frontlineSmsConfigService);
		} else {
			String appointmentDetails = operationMetadataService.getAppointmentConfirmationData(appointmentRequest);
			return MessageFormat.format(ConstantUtils.MESSAGE_OPCAO_INVALIDA, StringUtils
					.remove(MessageFormat.format(MessageUtils.getMenuText(currentMenu), appointmentDetails), "CON"));

		}
	}

	private String handleMenuDistrict(Menu currentMenu, UssdRequest ussdRequest, CurrentState currentState,
			MenuService menuService, OperationMetadataService operationMetadataService,
			SessionDataService sessionDataService, InfoMessageService infoMessageService,
			FrontlineSmsConfigService frontlineSmsConfigService) {
		int selectedProvinceId = Integer.parseInt(
				sessionDataService.findByCurrentStateIdAndAttrName(currentState.getId(), "provinceId").getAttrValue());

		if (ussdRequest.getText().equalsIgnoreCase(ConstantUtils.OPTION_VER_MAIS)) {

			return getDistrictsMenu(selectedProvinceId, allProvinces, ussdRequest, currentMenu);
		} else if (mapDistricts.containsKey(ussdRequest.getText())) {
			// ver AQUIIIII
			SessionData sd = new SessionData(currentState.getId(), "districtId",
					mapDistricts.get(ussdRequest.getText()).getId() + "");
			sessionDataService.saveSessionData(sd);
			ussdRequest.setText(mapDistricts.get(ussdRequest.getText()).getId() + "");
			lastIndex = pagingSize;
			startIndex = 0;

		} else {
			return MessageFormat.format(ConstantUtils.MESSAGE_OPCAO_INVALIDA, StringUtils
					.remove(getDistrictsMenu(selectedProvinceId, allProvinces, ussdRequest, currentMenu), "CON "));

		}
		return navegate(currentMenu, ussdRequest, currentState, menuService, operationMetadataService,
				sessionDataService, infoMessageService, frontlineSmsConfigService);
	}

	private String getClinicsByDistrictMenu(UssdRequest request, CurrentState currentState,
			SessionDataService sessionDataService, Menu menu) {
		long districtId = Long.parseLong(
				sessionDataService.findByCurrentStateIdAndAttrName(currentState.getId(), "districtId").getAttrValue());
		return getClinicsByDistrictId(districtId, request, menu);
	}

	private String handleMenuProvince(Menu currentMenu, UssdRequest ussdRequest, CurrentState currentState,
			MenuService menuService, OperationMetadataService operationMetadataService,
			SessionDataService sessionDataService, InfoMessageService infoMessageService,
			FrontlineSmsConfigService frontlineSmsConfigService) throws Throwable {
		if (mapProvinces.containsKey(ussdRequest.getText())) {
			SessionData sd = new SessionData(currentState.getId(), "provinceId",
					mapProvinces.get(ussdRequest.getText()).getId() + "");
			sessionDataService.saveSessionData(sd);
			ussdRequest.setText(mapProvinces.get(ussdRequest.getText()).getId() + "");
		} else {
			return MessageFormat.format(ConstantUtils.MESSAGE_OPCAO_INVALIDA, StringUtils
					.remove(MessageFormat.format(MessageUtils.getMenuText(currentMenu), getProvincesMenu()), "CON "));
		}
		return navegate(currentMenu, ussdRequest, currentState, menuService, operationMetadataService,
				sessionDataService, infoMessageService, frontlineSmsConfigService);
	}

	// ================================== Private Behaviour ==============
	private String handleMenuConfirmationPage(UssdRequest ussdRequest, CurrentState currentState, Menu menu,
			OperationMetadataService operationMetadataService, MenuService menuService,
			SessionDataService sessionDataService) {
		// apresenta dados na tela de confirmacao
		currentState.setIdMenu(menu.getId());
		menuService.saveCurrentState(currentState);
		appointmentRequest = operationMetadataService.createAppointmentRequestByMetadatas(ussdRequest,
				currentState.getLocation(), currentState, menu);

		appointmentRequest.setUtente(Long.parseLong(
				sessionDataService.findByCurrentStateIdAndAttrName(currentState.getId(), "utenteId").getAttrValue()));
		appointmentRequest.setClinicName(
				sessionDataService.findByCurrentStateIdAndAttrName(currentState.getId(), "clinicName").getAttrValue());
		appointmentRequest.setId(Long.parseLong(sessionDataService
				.findByCurrentStateIdAndAttrName(currentState.getId(), "appointmentId").getAttrValue()));

		String appointmentDetails = operationMetadataService.getAppointmentConfirmationData(appointmentRequest);
		return MessageFormat.format(MessageUtils.getMenuText(menu), appointmentDetails);

	}

	private String getAppointmentDetails(CurrentState currentState, MenuService menuService,
			SessionDataService sessionDataService, Menu nextMenu) {

		String utenteId = sessionDataService.findByCurrentStateIdAndAttrName(currentState.getId(), "utenteId")
				.getAttrValue();
		AppointmentSearchResponse app = RestClient.getInstance().getAppointmentByUtenteId(utenteId);
		if (app.getStatusCode() == 200 || app.getStatusCode() == 201) {

			sessionDataService.saveClinicOnSessionData(app.getClinic(), currentState.getId());
			String details = MessageFormat.format(ConstantUtils.MESSAGE_APPOINTMENT_DETAILS,
					DateUtils.getSimpleDateFormat(app.getAppointmentDate()), app.getClinic().getName(),
					app.getStatus());
			return MessageFormat.format(MessageUtils.getMenuText(nextMenu), details);

		} else {
			MenuUtils.resetSession(currentState, menuService);
			return ConstantUtils.MESSAGE_UNEXPECTED_ERROR;
		}
	}

	private String autenticate(Menu currentMenu, UssdRequest request, CurrentState currentState,
			MenuService menuService, SessionDataService sessionDataService) {
		// Invocar o servico de autenticacao
		UtenteSearchResponse response = RestClient.getInstance().getUtenteBySystemNumber(request.getText());
		String phoneNumber = StringUtils.trim(StringUtils.remove(request.getPhoneNumber(), "+258"));

		if (response.getStatusCode() == 200 && phoneNumber.equalsIgnoreCase(response.getCellNumber())) {
			currentState.setIdMenu(currentMenu.getNextMenuId());
			menuService.saveCurrentState(currentState);
			sessionDataService.saveSessionData(response, currentState.getId());
			// session.setAttribute("utenteSession", response);
			// return getNextMenuText(currentState, currentMenu, menuService);
			Menu nextMenu = menuService.findMenuById(currentMenu.getNextMenuId());
			return getAppointmentDetails(currentState, menuService, sessionDataService, nextMenu);
		} else {
			return MessageFormat.format(ConstantUtils.MESSAGE_LOGIN_FAILED,
					StringUtils.remove(MessageUtils.getMenuText(currentMenu), "CON "));
		}
	}

	private String getNextMenuText(CurrentState currentState, Menu currentMenu, MenuService menuService) {
		// Passa para o proximo menu se a opcao != 0
		// se tiver apenas a opcao (0. Voltar) e o utilizador introduzir o nome por
		// exemplo, passa para o proximo menu
		currentState.setIdMenu(currentMenu.getNextMenuId());
		menuService.saveCurrentState(currentState);
		// pega o proximo menu
		Menu nextMenu = menuService.findMenuById(currentMenu.getNextMenuId());
		return MessageUtils.getMenuText(nextMenu);
	}

	private String getProvincesMenu() {
		String provinces = StringUtils.EMPTY;
		mapProvinces = new HashMap<>();
		allProvinces = RestClient.getInstance().getAllProvinces();
		int key = 1;
		for (Province province : allProvinces) {
			provinces += key + ". " + province.getDescription() + "\n";
			mapProvinces.put(String.valueOf(key), province);
			key++;
		}
		return provinces;
	}

	private String getDistrictsMenu(int idProvince, List<Province> allProvinces, UssdRequest ussdRequest,
			Menu currentMenu) {

		if (!ussdRequest.getText().equalsIgnoreCase(ConstantUtils.OPTION_VER_MAIS)
				&& !ussdRequest.getText().equalsIgnoreCase(ConstantUtils.OPTION_VOLTAR)) {
			startIndex = 0;
			lastIndex = pagingSize;
			districtList = new ArrayList<District>();
			districtList = allProvinces.stream().filter(p -> p.getId() == (idProvince)).findFirst().get().getDistricts()
					.stream().sorted(Comparator.comparing(District::getDescription)).collect(Collectors.toList());
			int key = 1;
			for (District dis : districtList) {
				dis.setOption(key + "");
				key++;
			}

			String menuText = getFormatedDistrictByList(districtList.subList(startIndex,
					pagingSize > districtList.size() ? districtList.size() : pagingSize));

			startIndex = lastIndex;
			lastIndex = startIndex + pagingSize;

			return MessageFormat.format(MessageUtils.getMenuText(currentMenu), menuText);
			// return menuText;

		} else {
			if (lastIndex > districtList.size()) {
				String menu = getFormatedDistrictByList(districtList.subList(startIndex, districtList.size()));
				List<Menu> menus = currentMenu.getMenuItems();
				menus.removeIf(t -> t.getOption().equalsIgnoreCase("99"));
				return MessageFormat.format(MessageUtils.getMenuText(currentMenu), menu);
			}
			String menuText = getFormatedDistrictByList(districtList.subList(startIndex, lastIndex));
			startIndex = lastIndex;
			lastIndex = startIndex + pagingSize;
			return MessageFormat.format(MessageUtils.getMenuText(currentMenu), menuText);
		}
	}

	private String getFormatedDistrictByList(List<District> list) {
		mapDistricts = new HashMap<String, District>();
		String menuDistricts = StringUtils.EMPTY;

		for (District item : list) {
			menuDistricts += item.getOption() + ". " + item.getDescription() + "\n";
			mapDistricts.put(String.valueOf(item.getOption()), item);
		}

		return menuDistricts;
	}

	// Devolve a lista de clinicas, sobre uma paginacao definida
	private String getClinicsByDistrictId(long districtId, UssdRequest ussdRequest, Menu currentMenu) {

		if (ussdRequest == null || !ussdRequest.getText().equalsIgnoreCase(ConstantUtils.OPTION_VER_MAIS)) {

			clinicsList = RestClient.getInstance().getClinicsByDistrict(districtId).getClinics().stream()
					.sorted(Comparator.comparing(Clinic::getName)).collect(Collectors.toList());
			int key = 1;
			for (Clinic dis : clinicsList) {
				dis.setOption(key + "");
				key++;
			}
			Integer lastElementIndex = pagingSize > clinicsList.size() ? clinicsList.size() : pagingSize;
			String menuText = getClinicsMenu(clinicsList.subList(0, lastElementIndex));

			startIndex = lastElementIndex;
			lastIndex = startIndex + pagingSize;
			return MessageFormat.format(MessageUtils.getMenuText(currentMenu), menuText);

		} else {
			if (lastIndex > clinicsList.size()) {
				String menu = getClinicsMenu(clinicsList.subList(startIndex, clinicsList.size()));
				List<Menu> menus = currentMenu.getMenuItems();
				menus.removeIf(t -> t.getOption().equalsIgnoreCase("99"));
				return MessageFormat.format(MessageUtils.getMenuText(currentMenu), menu);

			}
			String menuText = getClinicsMenu(clinicsList.subList(startIndex, lastIndex));
			startIndex = lastIndex;
			lastIndex = lastIndex + pagingSize;
			return MessageFormat.format(MessageUtils.getMenuText(currentMenu), menuText);
		}
	}

	private String getClinicsMenu(List<Clinic> list) {
		mapClinics = new HashMap<String, Clinic>();
		String menuClinics = StringUtils.EMPTY;

		for (Clinic item : list) {
			menuClinics += item.getOption() + ". " + item.getName() + "\n";
			mapClinics.put(String.valueOf(item.getOption()), item);
		}

		return menuClinics;
	}

	private String getClinicsForSms(List<Clinic> list) {
		String menuClinics = StringUtils.EMPTY;
		int i = 1;
		for (Clinic item : list) {
			menuClinics += i + "." + item.getName() + ",";
			i++;
		}

		return menuClinics;
	}
}
