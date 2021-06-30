package org.openelisglobal.dataexchange.order.controller;

import java.sql.Date;
import java.util.List;
import java.util.stream.Collectors;

import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;

import org.apache.commons.validator.GenericValidator;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.ServiceRequest;
import org.hl7.fhir.r4.model.Task;
import org.openelisglobal.common.controller.BaseController;
import org.openelisglobal.common.services.DisplayListService;
import org.openelisglobal.common.services.DisplayListService.ListType;
import org.openelisglobal.common.util.DateUtil;
import org.openelisglobal.dataexchange.fhir.FhirConfig;
import org.openelisglobal.dataexchange.fhir.FhirUtil;
import org.openelisglobal.dataexchange.order.ElectronicOrderSortOrderCategoryConvertor;
import org.openelisglobal.dataexchange.order.form.ElectronicOrderViewForm;
import org.openelisglobal.dataexchange.order.valueholder.ElectronicOrder;
import org.openelisglobal.dataexchange.order.valueholder.ElectronicOrder.SortOrder;
import org.openelisglobal.dataexchange.order.valueholder.ElectronicOrderDisplayItem;
import org.openelisglobal.dataexchange.service.order.ElectronicOrderService;
import org.openelisglobal.organization.service.OrganizationService;
import org.openelisglobal.organization.valueholder.Organization;
import org.openelisglobal.patient.service.PatientService;
import org.openelisglobal.patient.valueholder.Patient;
import org.openelisglobal.sample.service.SampleService;
import org.openelisglobal.sample.valueholder.Sample;
import org.openelisglobal.statusofsample.service.StatusOfSampleService;
import org.openelisglobal.test.service.TestService;
import org.openelisglobal.test.valueholder.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.ModelAndView;

import ca.uhn.fhir.rest.client.api.IGenericClient;

@Controller
public class ElectronicOrdersController extends BaseController {

    private static final String[] ALLOWED_FIELDS = new String[] { "searchType", "searchValue", "startDate", "endDate",
            "testIds", "statusId" };

    @Autowired
    private StatusOfSampleService statusOfSampleService;
    @Autowired
    private ElectronicOrderService electronicOrderService;
    @Autowired
    private PatientService patientService;
    @Autowired
    private TestService testService;
    @Autowired
    private OrganizationService organizationService;
    @Autowired
    private SampleService sampleService;
    @Autowired
    private FhirUtil fhirUtil;
    @Autowired
    private FhirConfig fhirConfig;

    @InitBinder
    public void initBinder(final WebDataBinder webdataBinder) {
        webdataBinder.registerCustomEditor(ElectronicOrder.SortOrder.class,
                new ElectronicOrderSortOrderCategoryConvertor());
        webdataBinder.setAllowedFields(ALLOWED_FIELDS);
    }

    @RequestMapping(value = "/ElectronicOrders", method = RequestMethod.GET)
    public ModelAndView showElectronicOrders(HttpServletRequest request,
            @ModelAttribute("form") @Valid ElectronicOrderViewForm form, BindingResult result) {
        form.setReferralFacilitySelectionList(
                DisplayListService.getInstance().getList(ListType.REFERRAL_ORGANIZATIONS));
        form.setTestSelectionList(DisplayListService.getInstance().getList(ListType.ORDERABLE_TESTS));
        form.setStatusSelectionList(DisplayListService.getInstance().getList(ListType.ELECTRONIC_ORDER_STATUSES));

        if (form.getSearchType() != null) {
            List<ElectronicOrder> electronicOrders;
            List<ElectronicOrderDisplayItem> eOrderDisplayItems;

            electronicOrders = searchForElectronicOrders(form);
            eOrderDisplayItems = convertToDisplayItem(electronicOrders);

            form.setSearchFinished(true);
            form.setEOrders(eOrderDisplayItems);
        }

        return findForward(FWD_SUCCESS, form);
    }

    private List<ElectronicOrderDisplayItem> convertToDisplayItem(List<ElectronicOrder> electronicOrders) {
        return electronicOrders.stream().map(e -> convertToDisplayItem(e)).collect(Collectors.toList());
    }

    private ElectronicOrderDisplayItem convertToDisplayItem(ElectronicOrder electronicOrder) {
        ElectronicOrderDisplayItem displayItem = new ElectronicOrderDisplayItem();

        IGenericClient fhirClient = fhirUtil.getFhirClient(fhirConfig.getLocalFhirStorePath());

        ServiceRequest serviceRequest = fhirClient.read().resource(ServiceRequest.class)
                .withId(electronicOrder.getExternalId()).execute();
        Test test = null;
        for (Coding coding : serviceRequest.getCode().getCoding()) {
            if (coding.getSystem().equalsIgnoreCase("http://loinc.org")) {
                List<Test> tests = testService.getActiveTestsByLoinc(coding.getCode());
                if (tests.size() != 0) {
                    test = tests.get(0);
                    break;
                }
            }
        }
        Task task = fhirUtil.getFhirParser().parseResource(Task.class, electronicOrder.getData());

//       Patient patient =  fhirClient.read().resource(Patient.class).withId(serviceRequest.getSubject().getReference()).execute();
        Patient patient = patientService.getByExternalId(serviceRequest.getSubject().getReferenceElement().getIdPart());
        org.hl7.fhir.r4.model.Patient fhirPatient = fhirClient.read().resource(org.hl7.fhir.r4.model.Patient.class)
                .withId(serviceRequest.getSubject().getReferenceElement().getIdPart()).execute();

        String passportNumber = "";
        String subjectNumber = patientService.getSubjectNumber(patient);
        for (Identifier identifier : fhirPatient.getIdentifier()) {
            if ("passport".equals(identifier.getSystem())) {
                passportNumber = GenericValidator.isBlankOrNull(identifier.getId()) ? passportNumber
                        : identifier.getId();
            }
            if ((fhirConfig.getOeFhirSystem() + "/pat_subjectNumber").equals(identifier.getSystem())) {
                subjectNumber = GenericValidator.isBlankOrNull(identifier.getId()) ? subjectNumber : identifier.getId();
            }
        }

        Organization organization = organizationService
                .getOrganizationByFhirId(task.getRestriction().getRecipientFirstRep().getIdElement().getId());

        Sample sample = sampleService.getSampleByReferringId(electronicOrder.getExternalId());
        displayItem.setElectronicOrderId(electronicOrder.getId());
        displayItem.setExternalOrderId(electronicOrder.getExternalId());
        displayItem.setRequestDateDisplay(DateUtil.formatDateTimeAsText(task.getAuthoredOn()));
        if (patient != null && patient.getPerson() != null) {
            displayItem.setPatientLastName(patient.getPerson().getLastName());
            displayItem.setPatientFirstName(patient.getPerson().getFirstName());
            displayItem.setPatientNationalId(patient.getNationalId());
        }
        if (organization != null) {
            displayItem.setRequestingFacility(organization.getOrganizationName());
        }
        displayItem.setStatus(statusOfSampleService.get(electronicOrder.getStatusId()).getDefaultLocalizedName());
        if (test != null) {
            displayItem.setTestName(test.getLocalizedTestName().getLocalizedValue());
        }
        if (serviceRequest.getRequisition() != null) {
            displayItem.setReferringLabNumber(serviceRequest.getRequisition().getValue());
        }
        displayItem.setPassportNumber(passportNumber);
        displayItem.setSubjectNumber(subjectNumber);
        if (sample != null) {
            displayItem.setLabNumber(sample.getAccessionNumber());
        }

        return displayItem;
    }

    private List<ElectronicOrder> searchForElectronicOrders(ElectronicOrderViewForm form) {
        switch (form.getSearchType()) {
        case IDENTIFIER:
            return electronicOrderService.getAllElectronicOrdersContainingValueOrderedBy(form.getSearchValue(),
                    SortOrder.LAST_UPDATED_ASC);
        case DATE_STATUS:
            Date startDate = GenericValidator.isBlankOrNull(form.getStartDate()) ? null
                    : DateUtil.convertStringDateToSqlDate(form.getStartDate());
            Date endDate = GenericValidator.isBlankOrNull(form.getEndDate()) ? null
                    : DateUtil.convertStringDateToSqlDate(form.getEndDate());
            return electronicOrderService.getAllElectronicOrdersByDateAndStatus(startDate, endDate, form.getStatusId(),
                    SortOrder.LAST_UPDATED_ASC);
        default:
            return null;
        }

    }

    @Override
    protected String findLocalForward(String forward) {
        if (FWD_SUCCESS.equals(forward)) {
            return "electronicOrderViewDefinition";
        } else {
            return "PageNotFound";
        }
    }

    @Override
    protected String getPageTitleKey() {
        return "eorder.browse.title";
    }

    @Override
    protected String getPageSubtitleKey() {
        return "eorder.browse.title";
    }
}
