package sonata.kernel.WimAdaptor.wrapper.openstack;

import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import sonata.kernel.WimAdaptor.commons.DeployServiceData;
import sonata.kernel.WimAdaptor.commons.DeployServiceResponse;
import sonata.kernel.WimAdaptor.commons.ServiceRecord;
import sonata.kernel.WimAdaptor.commons.Status;
import sonata.kernel.WimAdaptor.commons.VduRecord;
import sonata.kernel.WimAdaptor.commons.VnfRecord;
import sonata.kernel.WimAdaptor.commons.VnfcInstance;
import sonata.kernel.WimAdaptor.commons.heat.HeatServer;
import sonata.kernel.WimAdaptor.commons.heat.HeatTemplate;
import sonata.kernel.WimAdaptor.commons.heat.StackComposition;
import sonata.kernel.WimAdaptor.commons.vnfd.VirtualDeploymentUnit;
import sonata.kernel.WimAdaptor.commons.vnfd.VnfDescriptor;
import sonata.kernel.WimAdaptor.wrapper.WrapperStatusUpdate;

import java.util.Hashtable;
import java.util.UUID;

public class DeployServiceFsm implements Runnable {

  private String sid;
  private DeployServiceData data;
  private OpenStackHeatWrapper wrapper;
  private OpenStackHeatClient client;
  private HeatTemplate stack;
  private static final int maxCounter = 10;


  /**
   * Return an object that handles the FSM needed to deploy a service in OpenStackHeat.
   * 
   * @param wrapper the Compute wrapper issuing this FSM
   * @param client the OpenStack client to use for the deployment
   * @param sid the session ID of the service platform call
   * @param data the payload of the service platform call
   * @param stack the HeatStack result of the translation
   */
  public DeployServiceFsm(OpenStackHeatWrapper wrapper, OpenStackHeatClient client, String sid,
      DeployServiceData data, HeatTemplate stack) {

    this.wrapper = wrapper;
    this.client = client;
    this.sid = sid;
    this.data = data;
    this.stack = stack;
  }

  @Override
  public void run() {
    DeployServiceResponse response = new DeployServiceResponse();
    try {
      System.out.println("[OS-Deploy-FSM] Deploying new stack");
      ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
      mapper.disable(SerializationFeature.WRITE_EMPTY_JSON_ARRAYS);
      mapper.enable(SerializationFeature.WRITE_ENUMS_USING_TO_STRING);
      mapper.disable(SerializationFeature.WRITE_NULL_MAP_VALUES);
      mapper.setSerializationInclusion(Include.NON_NULL);
      System.out.println("[OS-Deploy-FSM]   Serializing stack...");

      String stackString = mapper.writeValueAsString(stack);

      String stackName = data.getNsd().getName() + data.getNsd().getInstanceUuid();
      System.out.println("[OS-Deploy-FSM]   Pushing stack to Heat...");
      String instanceUuid = client.createStack(stackName, stackString);

      if (instanceUuid == null) {
        WrapperStatusUpdate update = new WrapperStatusUpdate(this.sid, "FAIL",
            "{\"message\":\"unable to contact the VIM to instantiate the service\"}");
        wrapper.markAsChanged();
        wrapper.notifyObservers(update);
        return;
      }
      int counter = 0;
      int wait = 1000;
      String status = null;
      while ((status == null || !status.equals("CREATE_COMPLETE"))
          && counter < DeployServiceFsm.maxCounter) {
        status = client.getStackStatus(stackName, instanceUuid);
        System.out.println("[OS-Deploy-FSM]   Status of stack " + instanceUuid + ": " + status);
        if (status != null && status.equals("CREATE_COMPLETE")) {
          break;
        }
        try {
          Thread.sleep(wait);
        } catch (InterruptedException e) {
          // TODO Auto-generated catch block
          e.printStackTrace();
        }
        counter++;
        wait *= 2;
      }

      if (status == null) {
        WrapperStatusUpdate update = new WrapperStatusUpdate(this.sid, "FAIL",
            "{\"message\":\"unable to contact the VIM to check the instantiation status\"}");
        wrapper.markAsChanged();
        wrapper.notifyObservers(update);
        return;
      }

      counter = 0;
      wait = 1000;
      StackComposition composition = null;
      while (composition == null && counter < DeployServiceFsm.maxCounter) {
        System.out.println("[OS-Deploy-FSM]   getting composition of stack " + instanceUuid);
        composition = client.getStackComposition(stackName, instanceUuid);
        try {
          Thread.sleep(wait);
        } catch (InterruptedException e) {
          // TODO Auto-generated catch block
          e.printStackTrace();
        }
        counter++;
        wait *= 2;
      }

      if (composition == null) {
        WrapperStatusUpdate update = new WrapperStatusUpdate(this.sid, "FAIL",
            "{\"message\":\"unable to contact the VIM to check the instantiation status\"}");
        wrapper.markAsChanged();
        wrapper.notifyObservers(update);
        return;
      }

      // Aux data structures for efficient mapping
      Hashtable<String, VnfDescriptor> vnfTable = new Hashtable<String, VnfDescriptor>();
      Hashtable<String, VirtualDeploymentUnit> vduTable =
          new Hashtable<String, VirtualDeploymentUnit>();
      Hashtable<String, VduRecord> vdurTable = new Hashtable<String, VduRecord>();

      // Create the response
      System.out.println("[OS-Deploy-FSM]   creating deploy response");
      ServiceRecord sr = new ServiceRecord();
      sr.setUuid(data.getNsd().getUuid());
      sr.setStatus(Status.offline);
      sr.setInstanceUuid(data.getNsd().getInstanceUuid());
      for (VnfDescriptor vnf : data.getVnfdList()) {
        vnfTable.put(vnf.getName(), vnf);
        VnfRecord vnfr = new VnfRecord();
        vnfr.setDescriptorVersion("vnfr-schema-01");
        vnfr.setDescriptorReferenceName(vnf.getName());
        vnfr.setDescriptorReferenceVendor(vnf.getVendor());
        vnfr.setDescriptorReferenceVersion(vnf.getVersion());
        vnfr.setStatus(Status.offline);
        // TODO addresses are added next step
        // vnfr.setVnfAddress("0.0.0.0");
        vnfr.setUuid(vnf.getUuid());
        vnfr.setInstanceUuid(vnf.getInstanceUuid());
        for (VirtualDeploymentUnit vdu : vnf.getVirtualDeploymentUnits()) {
          vduTable.put(vnf.getName() + ":" + vdu.getId(), vdu);
          VduRecord vdur = new VduRecord();
          vdur.setId(UUID.randomUUID().toString());
          vdur.setNumberOfInstances(1);
          vdur.setVduReference(vnf.getName() + ":" + vdu.getId());
          vdur.setVmImage(vdu.getVmImage());
          vdurTable.put(vdur.getVduReference(), vdur);
          vnfr.addVdu(vdur);
        }
        response.addVnfRecord(vnfr);
      }
      response.setNsr(sr);

      // Put each composition.server data in a VNFC instance in the relevant VDU

      for (HeatServer server : composition.getServers()) {
        String[] identifiers = server.getServerName().split(":");
        String vnfName = identifiers[0];
        String vduName = identifiers[1];
        String instanceId = identifiers[2];

        VnfcInstance vnfc = new VnfcInstance();
        vnfc.setId(instanceId);
        vnfc.setVimId(data.getVimUuid());
        vnfc.setVcId(server.getServerId());
        VnfDescriptor referenceVnf = vnfTable.get(vnfName);
        VirtualDeploymentUnit referenceVdu = vduTable.get(vnfName + ":" + vduName);
        vnfc.setConnectionPoints(referenceVdu.getConnectionPoints());
        VduRecord referenceVdur =
            vdurTable.get(referenceVnf.getName() + ":" + referenceVdu.getId());
        referenceVdur.addVnfcInstance(vnfc);
      }

      // TODO add each composition.ports information in the response. The IP (and maybe MAC address)
      // fields in the NSR are still to be defined

      response.setInstanceName(stackName);
      response.setInstanceVimUuid(instanceUuid);
      response.setStatus(Status.offline);
      String body = mapper.writeValueAsString(response);
      System.out.println("[OS-Deploy-FSM]   response created");

      WrapperStatusUpdate update = new WrapperStatusUpdate(this.sid, "SUCCESS", body);
      wrapper.markAsChanged();
      wrapper.notifyObservers(update);
    } catch (JsonProcessingException e) {
      e.printStackTrace();
      response.setErrorCode("TranslationError");
    }
  }

}
