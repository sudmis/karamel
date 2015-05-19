/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package se.kth.karamel.backend.launcher.amazon;

import com.google.common.base.Optional;
import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.log4j.Logger;
import org.jclouds.aws.AWSResponseException;
import org.jclouds.aws.ec2.compute.AWSEC2TemplateOptions;
import org.jclouds.aws.ec2.features.AWSSecurityGroupApi;
import org.jclouds.aws.ec2.options.CreateSecurityGroupOptions;
import org.jclouds.compute.RunNodesException;
import org.jclouds.compute.domain.NodeMetadata;
import org.jclouds.compute.domain.TemplateBuilder;
import org.jclouds.ec2.domain.KeyPair;
import org.jclouds.ec2.domain.SecurityGroup;
import org.jclouds.ec2.features.SecurityGroupApi;
import org.jclouds.net.domain.IpPermission;
import org.jclouds.net.domain.IpProtocol;
import org.jclouds.rest.AuthorizationException;
import se.kth.karamel.backend.running.model.GroupRuntime;
import se.kth.karamel.backend.running.model.MachineRuntime;
import se.kth.karamel.common.Settings;
import se.kth.karamel.common.exception.KaramelException;
import se.kth.karamel.client.model.Ec2;
import se.kth.karamel.common.Confs;
import se.kth.karamel.common.Ec2Credentials;
import se.kth.karamel.common.SshKeyPair;
import se.kth.karamel.common.exception.InvalidEc2CredentialsException;

/**
 * @author kamal
 */
public final class Ec2Launcher {

  private static final Logger logger = Logger.getLogger(Ec2Launcher.class);
  public static boolean TESTING = true;
  public final Ec2Context context;
  public final SshKeyPair sshKeyPair;

  public Ec2Launcher(Ec2Context context, SshKeyPair sshKeyPair) {
    this.context = context;
    this.sshKeyPair = sshKeyPair;
    logger.info(String.format("Access-key='%s'", context.getCredentials().getAccessKey()));
    logger.info(String.format("Public-key='%s'", sshKeyPair.getPublicKeyPath()));
    logger.info(String.format("Private-key='%s'", sshKeyPair.getPrivateKeyPath()));
  }

  public static Ec2Context validateCredentials(Ec2Credentials credentials) throws InvalidEc2CredentialsException {
    try {
      Ec2Context cxt = new Ec2Context(credentials);
      SecurityGroupApi securityGroupApi = cxt.getSecurityGroupApi();
      securityGroupApi.describeSecurityGroupsInRegion(Settings.PROVIDER_EC2_DEFAULT_REGION);
      return cxt;
    } catch (AuthorizationException e) {
      throw new InvalidEc2CredentialsException("accountid:" + credentials.getAccessKey(), e);
    }
  }

  public static Ec2Credentials readCredentials(Confs confs) {
    String accessKey = confs.getProperty(Settings.AWS_ACCESS_KEY);
    String secretKey = confs.getProperty(Settings.AWS_SECRET_KEY);
    Ec2Credentials credentials = null;
    if (accessKey != null && !accessKey.isEmpty() && secretKey != null && !accessKey.isEmpty()) {
      credentials = new Ec2Credentials();
      credentials.setAccessKey(accessKey);
      credentials.setSecretKey(secretKey);

    }
    return credentials;
  }

  public String createSecurityGroup(String clusterName, String groupName, Ec2 ec2, Set<String> ports) throws KaramelException {
    String uniqeGroupName = Settings.EC2_UNIQUE_GROUP_NAME(clusterName, groupName);
    logger.info(String.format("Creating security group '%s' ...", uniqeGroupName));
    if (context == null) {
      throw new KaramelException("Register your valid credentials first :-| ");
    }

    if (sshKeyPair == null) {
      throw new KaramelException("Choose your ssh keypair first :-| ");
    }

    Optional<? extends org.jclouds.ec2.features.SecurityGroupApi> securityGroupExt
            = context.getEc2api().getSecurityGroupApiForRegion(ec2.getRegion());
    if (securityGroupExt.isPresent()) {
      AWSSecurityGroupApi client = (AWSSecurityGroupApi) securityGroupExt.get();
      String groupId = null;
      if (ec2.getVpc() != null) {
        CreateSecurityGroupOptions csgos = CreateSecurityGroupOptions.Builder.vpcId(ec2.getVpc());
        groupId = client.createSecurityGroupInRegionAndReturnId(ec2.getRegion(), uniqeGroupName, uniqeGroupName, csgos);
      } else {
        groupId = client.createSecurityGroupInRegionAndReturnId(ec2.getRegion(), uniqeGroupName, uniqeGroupName);
      }

      if (!TESTING) {
        for (String port : ports) {
          Integer p = null;
          IpProtocol pr = null;
          if (port.contains("/")) {
            String[] s = port.split("/");
            p = Integer.valueOf(s[0]);
            pr = IpProtocol.valueOf(s[1]);
          } else {
            p = Integer.valueOf(port);
            pr = IpProtocol.TCP;
          }
          client.authorizeSecurityGroupIngressInRegion(ec2.getRegion(),
                  uniqeGroupName, pr, p, Integer.valueOf(port), "0.0.0.0/0");
          logger.info(String.format("Ports became open for '%s'", uniqeGroupName));
        }
      } else {
        IpPermission ippermission = IpPermission.builder().ipProtocol(IpProtocol.TCP).fromPort(0).toPort(65535).cidrBlock("0.0.0.0/0").build();
        client.authorizeSecurityGroupIngressInRegion(ec2.getRegion(), groupId, ippermission);
        logger.info(String.format("Ports became open for '%s'", uniqeGroupName));
      }
      logger.info(String.format("Security group '%s' was created :)", uniqeGroupName));
      return groupId;
    }
    return null;
  }

  public void uploadSshPublicKey(String keyPairName, Ec2 ec2, boolean removeOld) throws KaramelException {
    if (context == null) {
      throw new KaramelException("Register your valid credentials first :-| ");
    }

    if (sshKeyPair == null) {
      throw new KaramelException("Choose your ssh keypair first :-| ");
    }

    HashSet<String> regions = new HashSet();
    if (!regions.contains(ec2.getRegion())) {
      Set<KeyPair> keypairs = context.getKeypairApi().describeKeyPairsInRegion(ec2.getRegion(), new String[]{keyPairName});
      if (keypairs.isEmpty()) {
        logger.info(String.format("New keypair '%s' is being uploaded to EC2", keyPairName));
        context.getKeypairApi().importKeyPairInRegion(ec2.getRegion(), keyPairName, sshKeyPair.getPublicKey());
      } else {
        if (removeOld) {
          logger.info(String.format("Removing the old keypair '%s' and uploading the new one ...", keyPairName));
          context.getKeypairApi().deleteKeyPairInRegion(ec2.getRegion(), keyPairName);
          context.getKeypairApi().importKeyPairInRegion(ec2.getRegion(), keyPairName, sshKeyPair.getPublicKey());
        }
      }
      regions.add(ec2.getRegion());
    }
  }

  public List<MachineRuntime> forkMachines(String keyPairName, GroupRuntime mainGroup,
          Set<String> securityGroupIds, int totalSize, Ec2 ec2) throws KaramelException {
    String uniqeGroupName = Settings.EC2_UNIQUE_GROUP_NAME(mainGroup.getCluster().getName(), mainGroup.getName());
    List<String> allVmNames = Settings.EC2_UNIQUE_VM_NAMES(mainGroup.getCluster().getName(), mainGroup.getName(), totalSize);
    logger.info(String.format("Start forking %d machine(s) for '%s' ...", totalSize, uniqeGroupName));

    if (context == null) {
      throw new KaramelException("Register your valid credentials first :-| ");
    }

    if (sshKeyPair == null) {
      throw new KaramelException("Choose your ssh keypair first :-| ");
    }
    AWSEC2TemplateOptions options = context.getComputeService().templateOptions().as(AWSEC2TemplateOptions.class);
    if (ec2.getPrice() != null) {
      options.spotPrice(ec2.getPrice());
    }

    boolean succeed = false;
    int tries = 0;
    Set<NodeMetadata> successfulNodes = Sets.newLinkedHashSet();
    List<String> unforkedVmNames = new ArrayList<>();
    List<String> toBeForkedVmNames;
    unforkedVmNames.addAll(allVmNames);
    Map<NodeMetadata, Throwable> failedNodes = Maps.newHashMap();
    while (!succeed && tries < Settings.EC2_RETRY_MAX) {
      int requestSize = totalSize - successfulNodes.size();
      if (requestSize > Settings.EC2_MAX_FORK_VMS_PER_REQUEST) {
        requestSize = Settings.EC2_MAX_FORK_VMS_PER_REQUEST;
        toBeForkedVmNames = unforkedVmNames.subList(0, Settings.EC2_MAX_FORK_VMS_PER_REQUEST);
      } else {
        toBeForkedVmNames = unforkedVmNames;
      }
      TemplateBuilder template = context.getComputeService().templateBuilder();
      options.keyPair(keyPairName);
      options.as(AWSEC2TemplateOptions.class).securityGroupIds(securityGroupIds);
      options.nodeNames(toBeForkedVmNames);
      if (ec2.getSubnet() != null) {
        options.as(AWSEC2TemplateOptions.class).subnetId(ec2.getSubnet());
      }
      template.options(options);
      template.os64Bit(true);
      template.hardwareId(ec2.getType());
      template.imageId(ec2.getRegion() + "/" + ec2.getImage());
      template.locationId(ec2.getRegion());
      tries++;
      Set<NodeMetadata> succ = new HashSet<>();
      try {
        logger.info(String.format("Forking %d machine(s) for '%s', so far(succeeded:%d, failed:%d, total:%d)", requestSize, uniqeGroupName, successfulNodes.size(), failedNodes.size(), totalSize));
        succ.addAll(context.getComputeService().createNodesInGroup(
                uniqeGroupName, requestSize, template.build()));
      } catch (RunNodesException ex) {
        addSuccessAndLostNodes(ex, succ, failedNodes);
      } catch (AWSResponseException e) {
        if ("InstanceLimitExceeded".equals(e.getError().getCode())) {
          throw new KaramelException("It seems your ec2 account has instance limit.. if thats the case either decrease "
                  + "size of your cluster or increase the limitation of your account.", e);
        } else if ("InsufficientInstanceCapacity".equals(e.getError().getCode())) {
          throw new KaramelException(String.format("It seems your ec2 account doesn't have sufficent capacity for %s "
                  + "instances", ec2.getType()), e);
        } else {
          logger.error("", e);
        }
      } catch (IllegalStateException ex) {
        logger.error("", ex);
        logger.info(String.format("#%d Hurry up EC2!! I want machines for %s, will ask you again in %d ms :@", tries,
                uniqeGroupName, Settings.EC2_RETRY_INTERVAL), ex);
      }

      unforkedVmNames = findLeftVmNames(succ, unforkedVmNames);
      successfulNodes.addAll(succ);
      if (successfulNodes.size() < totalSize) {
        try {
          succeed = false;
          logger.info(String.format("So far we got %d successful-machine(s) and %d failed-machine(s) out of %d "
                  + "original-number for '%s'. Failed nodes will be killed later.", successfulNodes.size(), failedNodes.size(),
                  totalSize, uniqeGroupName));
          Thread.currentThread().sleep(Settings.EC2_RETRY_INTERVAL);
        } catch (InterruptedException ex1) {
          logger.error("", ex1);
        }
      } else {
        succeed = true;
        logger.info(String.format("Cool!! we got all %d machine(s) for '%s' |;-) we have %d failed-machines to kill before we go on..", totalSize, uniqeGroupName, failedNodes.size()));
        if (failedNodes.size() > 0) {
          cleanupFailedNodes(failedNodes);
        }
        List<MachineRuntime> machines = new ArrayList<>();
        for (NodeMetadata node : successfulNodes) {
          if (node != null) {
            MachineRuntime machine = new MachineRuntime(mainGroup);
            ArrayList<String> privateIps = new ArrayList();
            ArrayList<String> publicIps = new ArrayList();
            privateIps.addAll(node.getPrivateAddresses());
            publicIps.addAll(node.getPublicAddresses());
            machine.setEc2Id(node.getId());
            machine.setName(node.getName());
            machine.setPrivateIp(privateIps.get(0));
            machine.setPublicIp(publicIps.get(0));
            machine.setSshPort(node.getLoginPort());
            machine.setSshUser(node.getCredentials().getUser());
            machines.add(machine);
          }
        }
        return machines;
      }
    }
    throw new KaramelException(String.format("Couldn't fork machines for group'%s'", mainGroup.getName()));
  }

  private void cleanupFailedNodes(Map<NodeMetadata, Throwable> failedNodes) {
    if (failedNodes.size() > 0) {
      Set<String> lostIds = Sets.newLinkedHashSet();
      for (Map.Entry<NodeMetadata, Throwable> lostNode : failedNodes.entrySet()) {
        lostIds.add(lostNode.getKey().getId());
      }
      logger.info(String.format("Destroying failed nodes with ids: %s", lostIds.toString()));
      Set<? extends NodeMetadata> destroyedNodes = context.getComputeService().destroyNodesMatching(
              Predicates.in(failedNodes.keySet()));
      lostIds.clear();
      for (NodeMetadata destroyed : destroyedNodes) {
        lostIds.add(destroyed.getId());
      }
      logger.info("Failed nodes destroyed ;)");
    }
  }

  private void addSuccessAndLostNodes(RunNodesException rnex, Set<NodeMetadata> successfulNodes, Map<NodeMetadata, Throwable> lostNodes) {
    // workaround https://code.google.com/p/jclouds/issues/detail?id=923 
    // by ensuring that any nodes in the "NodeErrors" do not get considered 
    // successful 
    Set<? extends NodeMetadata> reportedSuccessfulNodes = rnex.getSuccessfulNodes();
    Map<? extends NodeMetadata, ? extends Throwable> errorNodesMap = rnex.getNodeErrors();
    Set<? extends NodeMetadata> errorNodes = errorNodesMap.keySet();

    // "actual" successful nodes are ones that don't appear in the errorNodes  
    successfulNodes.addAll(Sets.difference(reportedSuccessfulNodes, errorNodes));
    lostNodes.putAll(errorNodesMap);
  }

  private List<String> findLeftVmNames(Set<? extends NodeMetadata> successfulNodes, List<String> vmNames) {
    List<String> leftVmNames = new ArrayList<>();
    leftVmNames.addAll(vmNames);
    int unnamedVms = 0;
    for (NodeMetadata nodeMetadata : successfulNodes) {
      String nodeName = nodeMetadata.getName();
      if (leftVmNames.contains(nodeName)) {
        leftVmNames.remove(nodeName);
      } else {
        unnamedVms++;
      }
    }

    for (int i = 0; i < unnamedVms; i++) {
      if (leftVmNames.size() > 0) {
        logger.debug(String.format("Taking %s as one of the unnamed vms.", leftVmNames.get(0)));
        leftVmNames.remove(0);
      }
    }
    return leftVmNames;
  }

  public void cleanup(String clusterName, Set<String> vmIds, Set<String> vmNames, Map<String, String> groupRegion) throws KaramelException {
    if (context == null) {
      throw new KaramelException("Register your valid credentials first :-| ");
    }

    if (sshKeyPair == null) {
      throw new KaramelException("Choose your ssh keypair first :-| ");
    }
    Set<String> groupNames = new HashSet<>();
    for (Map.Entry<String, String> gp : groupRegion.entrySet()) {
      groupNames.add(Settings.EC2_UNIQUE_GROUP_NAME(clusterName, gp.getKey()));
    }
    logger.info(String.format("Killing following machines with names: \n %s \nor inside group names %s \nor with ids: %s", vmNames.toString(), groupNames, vmIds));
    logger.info(String.format("Killing all machines in groups: %s", groupNames.toString()));
    context.getComputeService().destroyNodesMatching(withPredicate(vmIds, vmNames, groupNames));
    logger.info(String.format("All machines destroyed in all the security groups. :) "));
    for (Map.Entry<String, String> gp : groupRegion.entrySet()) {
      String uniqueGroupName = Settings.EC2_UNIQUE_GROUP_NAME(clusterName, gp.getKey());
      for (SecurityGroup secgroup : context.getSecurityGroupApi().describeSecurityGroupsInRegion(gp.getValue())) {
        if (secgroup.getName().startsWith("jclouds#" + uniqueGroupName) || secgroup.getName().equals(uniqueGroupName)) {
          logger.info(String.format("Destroying security group '%s' ...", secgroup.getName()));
          boolean retry = false;
          int count = 0;
          do {
            count++;
            try {
              logger.info(String.format("#%d Destroying security group '%s' ...", count, secgroup.getName()));
              ((AWSSecurityGroupApi) context.getSecurityGroupApi()).deleteSecurityGroupInRegionById(gp.getValue(), secgroup.getId());
            } catch (IllegalStateException ex) {
              Throwable cause = ex.getCause();
              if (cause instanceof AWSResponseException) {
                AWSResponseException e = (AWSResponseException) cause;
                if (e.getError().getCode().equals("InvalidGroup.InUse") || e.getError().getCode().equals("DependencyViolation")) {
                  logger.info(String.format("Hurry up EC2!! terminate machines!! '%s', will retry in %d ms :@", uniqueGroupName, Settings.EC2_RETRY_INTERVAL));
                  retry = true;
                  try {
                    Thread.currentThread().sleep(Settings.EC2_RETRY_INTERVAL);
                  } catch (InterruptedException ex1) {
                    logger.error("", ex1);
                  }
                } else {
                  throw ex;
                }
              }
            }
          } while (retry);
          logger.info(String.format("The security group '%s' destroyed ^-^", secgroup.getName()));
        }
      }
    }
  }

  public static Predicate<NodeMetadata> withPredicate(final Set<String> ids, final Set<String> names, final Set<String> groupNames) {
    return new Predicate<NodeMetadata>() {
      @Override
      public boolean apply(NodeMetadata nodeMetadata) {
        String id = nodeMetadata.getId();
        String name = nodeMetadata.getName();
        String group = nodeMetadata.getGroup();
        return ((id != null && ids.contains(id)) || (name != null && names.contains(name) || (group != null && groupNames.contains(group))));
      }

      @Override
      public String toString() {
        return "machines predicate";
      }
    };
  }
}
