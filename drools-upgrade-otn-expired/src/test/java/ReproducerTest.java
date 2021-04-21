import fr.sle.AEvent;
import fr.sle.BEvent;
import fr.sle.Event;
import org.drools.compiler.kproject.ReleaseIdImpl;
import org.drools.core.base.ClassObjectType;
import org.drools.core.common.BaseNode;
import org.drools.core.common.NamedEntryPoint;
import org.drools.core.event.DebugAgendaEventListener;
import org.drools.core.event.DebugRuleRuntimeEventListener;
import org.drools.core.reteoo.ObjectTypeNode;
import org.drools.core.reteoo.builder.BuildContext;
import org.drools.core.spi.ObjectType;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.kie.api.KieBase;
import org.kie.api.KieServices;
import org.kie.api.builder.KieBuilder;
import org.kie.api.builder.KieFileSystem;
import org.kie.api.builder.KieModule;
import org.kie.api.definition.rule.Rule;
import org.kie.api.io.Resource;
import org.kie.api.runtime.KieContainer;
import org.kie.api.runtime.KieSession;
import org.kie.api.runtime.rule.FactHandle;
import org.kie.internal.io.ResourceFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.drools.core.time.SessionPseudoClock;


@RunWith(JUnit4.class)
public class ReproducerTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReproducerTest.class);

    private final String oldKmodule = "<kmodule xmlns=\"http://www.drools.org/xsd/kmodule\">\n" +
            "    <kbase name=\"kBase1\" default=\"true\" packages=\"fr.sle\" equalsBehavior=\"equality\" eventProcessingMode=\"stream\">\n" +
            "        <ksession name=\"ksession-oldmodule\" clockType=\"pseudo\" default=\"true\" />\n" +
            "    </kbase>\n" +
            "</kmodule>";

    private final String oldRule = "package fr.sle;\n" +
            "import fr.sle.AEvent;\n" +
            "import fr.sle.BEvent;\n" +
            "import fr.sle.Event;\n" +
            "\n" +
            "declare AEvent\n" +
            "   @role( event )\n" +
            "   @timestamp( eventTimestamp )\n" +
            "   @expires(3d)\n" +
            "end;\n" +
            "declare BEvent\n" +
            "   @role( event )\n" +
            "   @timestamp( eventTimestamp )\n" +
            "   @expires(30d)\n" +
            "end;" +
            "\n" +
            "rule \"detect AEvent\"\n" +
            "when $e: AEvent()\n" +
            "then\n" +
            "    System.out.println(\"detect AEvent\");\n" +
            "end\n" +
            "\n" +
            "rule \"detect Event\"\n" +
            "when $e: Event()\n" +
            "then\n" +
            "   System.out.println(\"detect Event\");\n" +
            "end\n" +
            "rule \"detect BEvent\"\n" +
            "when $e: BEvent()\n" +
            "then\n" +
            "   System.out.println(\"detect BEvent\");\n" +
            "end";

    final List<Resource> commonResources = Arrays.asList(
            ResourceFactory.newClassPathResource("/fr/sle/Event.class", Event.class),
            ResourceFactory.newClassPathResource("/fr/sle/AEvent.class", AEvent.class),
            ResourceFactory.newClassPathResource("/fr/sle/BEvent.class", BEvent.class)
    );

    /**
     * Old container, 0.9.0 Version
     */
    private KieContainer buildOldContainer(Collection<Resource> additionals) {
        final KieServices kieServices = KieServices.Factory.get();

        //old kiebase i.e 0.9.0
        final KieFileSystem oldKfs = kieServices.newKieFileSystem();
        oldKfs.writeKModuleXML(oldKmodule.getBytes());
        ReleaseIdImpl oldReleaseId = new ReleaseIdImpl("fr.sle", "drools-upgrade-otn-expired", "0.9.0");
        oldKfs.generateAndWritePomXML(oldReleaseId);
        commonResources.forEach(oldKfs::write);

        oldKfs.write("src/main/resources/fr/sle/rules.drl", oldRule.getBytes());    //Old Rules content

        if (additionals != null) {
            additionals.forEach(oldKfs::write);
        }

        KieBuilder oldKieBuilder = kieServices.newKieBuilder(oldKfs);
        oldKieBuilder.getKieModule(); //buildAll + checkError shortcut
        return kieServices.newKieContainer(oldReleaseId);
    }

    /**
     * Already prepared new 1.0-SNAPSHOT kmodule, common resources, pom and rules
     */
    private KieModule initNewModule(Consumer<KieFileSystem> additionalsConfig) throws IOException, URISyntaxException {
        ReleaseIdImpl releaseId = new ReleaseIdImpl("fr.sle", "drools-upgrade-otn-expired", "1.0-SNAPSHOT");
        final KieServices kieServices = KieServices.Factory.get();
        URI uri = this.getClass().getResource("META-INF/kmodule.xml").toURI();
        byte[] kmoduleXml = Files.readAllBytes(Paths.get(uri));
        KieFileSystem kfs = kieServices.newKieFileSystem();
        kfs.writeKModuleXML(kmoduleXml);
        kfs.generateAndWritePomXML(releaseId);
        commonResources.forEach(kfs::write);

        //Add the custom rules
        additionalsConfig.accept(kfs);

        final KieBuilder kieBuilder = kieServices.newKieBuilder(kfs);
        return kieBuilder.getKieModule();  //buildAll + checkError shortcut
    }

    /**
     * Assert that old module works as expected
     */
    @Test
    public void assertOldModule() {

        KieContainer kieContainer = buildOldContainer(null);

        KieSession kieSession = kieContainer.newKieSession();
        attachDebugListener(kieSession);

        NamedEntryPoint entryPoint = (NamedEntryPoint) kieSession.getEntryPoints().stream().findFirst().get();
        Map<ObjectType, ObjectTypeNode> objectTypeNodes = entryPoint.getEntryPointNode().getObjectTypeNodes();

        //Event
        ObjectTypeNode eventOTN = objectTypeNodes.get(new ClassObjectType(Event.class));
        Assert.assertEquals(-1L, eventOTN.getExpirationOffset());

        //AEvent
        ObjectTypeNode aEventOTN = objectTypeNodes.get(new ClassObjectType(AEvent.class));
        Assert.assertEquals(Duration.of(3, ChronoUnit.DAYS).toMillis() + 1, aEventOTN.getExpirationOffset());

        //BEvent
        ObjectTypeNode bEventOTN = objectTypeNodes.get(new ClassObjectType(BEvent.class));
        Assert.assertEquals(Duration.of(30, ChronoUnit.DAYS).toMillis() + 1, bEventOTN.getExpirationOffset());

        //pseudo clock initialization
        long now = System.currentTimeMillis();
        SessionPseudoClock sessionClock = kieSession.getSessionClock();
        sessionClock.advanceTime(now, TimeUnit.MILLISECONDS);

        AEvent aEvent = new AEvent(new Date(now), "A");
        kieSession.insert(aEvent);
        kieSession.fireAllRules();

        //AEvent expires
        sessionClock.advanceTime(Duration.of(4, ChronoUnit.DAYS).toMillis(), TimeUnit.MILLISECONDS);
        kieSession.fireAllRules();

        LOGGER.info("Number of facts in WM: " + kieSession.getFactCount());
        //AEvent is no longer in WM
        Assert.assertEquals(0, kieSession.getFactCount());

    }

    //Add a non Event dependent rule named "detect a fact"
    private String reproducerTest_rule = "package fr.sle;\n" +
            "import fr.sle.AEvent;\n" +
            "import fr.sle.BEvent;\n" +
            "import fr.sle.Event;\n" +
            "import fr.sle.AFact\n" +
            "\n" +
            "declare AEvent\n" +
            "   @role( event )\n" +
            "   @timestamp( eventTimestamp )\n" +
            "   @expires(3d)\n" +
            "end;\n" +
            "\n" +
            "declare BEvent\n" +
            "  @role( event )\n" +
            "  @timestamp( eventTimestamp )\n" +
            "  @expires(30d)\n" +
            "end;\n" +
            "\n" +
            "rule \"detect AEvent\"\n" +
            "when $e: AEvent()\n" +
            "then\n" +
            "    System.out.println(\"detect AEvent\");\n" +
            "end\n" +
            "\n" +
            "rule \"detect Event\"\n" +
            "when $e: Event()\n" +
            "then\n" +
            "   System.out.println(\"detect Event\");\n" +
            "end\n" +
            "\n" +
            "rule \"detect BEvent\"\n" +
            "when $e: BEvent()\n" +
            "then\n" +
            "    System.out.println(\"detect BEvent\");\n" +
            "end\n" +
            "\n" +
            "rule \"detect a fact\"\n" +
            "when $f: AFact()\n" +
            "then\n" +
            "  System.out.println(\"detect a fact\");\n" +
            "end";


    /**
     * The MAIN test of this reproducer.
     * During the upgrade phase, the whole base is rebuild. But OTN are already in the network as we previously built the kieBase in oldContainer
     * so {@link org.drools.core.impl.KnowledgeBaseImpl#updateDependentTypes} mess OTN's expirationOffset up.
     * Please put a breakpoint at the start of this method.
     */
    @Test
    public void reproducerMainTest() throws URISyntaxException, IOException {

        KieContainer oldContainer = buildOldContainer(Collections.emptyList());

        //Set the updated rules
        KieModule newKieModule = initNewModule((kfs) -> kfs.write("src/main/resources/fr/sle/rules.drl", reproducerTest_rule.getBytes()));

        oldContainer.getKieBase(); //init old KieBase

        //Upgrade
        oldContainer.updateToVersion(newKieModule.getReleaseId());

        //Assert
        KieContainer updatedContainer = oldContainer; //just naming

        KieSession kieSession = updatedContainer.newKieSession();
        NamedEntryPoint entryPoint = (NamedEntryPoint) kieSession.getEntryPoints().stream().findFirst().get();
        Map<ObjectType, ObjectTypeNode> objectTypeNodes = entryPoint.getEntryPointNode().getObjectTypeNodes();

        //AEvent
        ObjectTypeNode aEventOTN = objectTypeNodes.get(new ClassObjectType(AEvent.class));
        Assert.assertEquals(Duration.of(3, ChronoUnit.DAYS).toMillis() + 1, aEventOTN.getExpirationOffset());

        //BEvent
        ObjectTypeNode bEventOTN = objectTypeNodes.get(new ClassObjectType(BEvent.class));
        Assert.assertEquals(Duration.of(30, ChronoUnit.DAYS).toMillis() + 1, bEventOTN.getExpirationOffset());

        //WRONG --> Event type inherited max @Expires from children, so 30d (EventB). it should not.
        ObjectTypeNode eventTypeNode = objectTypeNodes.get(new ClassObjectType(Event.class));
//        Assert.assertEquals(Duration.of(30, ChronoUnit.DAYS).toMillis() + 1, eventTypeNode.getExpirationOffset());
        LOGGER.info("{} expiration offset is: {} ", eventTypeNode, eventTypeNode.getExpirationOffset());
        Assert.assertEquals(-1L, eventTypeNode.getExpirationOffset());
    }


    private String reproducerExtraTest_rule = "package fr.sle;\n" +
            "import fr.sle.AEvent;\n" +
            "import fr.sle.BEvent;\n" +
            "import fr.sle.Event;\n" +
            "import fr.sle.AFact\n" +
            "\n" +
            "declare AEvent\n" +
            "   @role( event )\n" +
            "   @timestamp( eventTimestamp )\n" +
            "   @expires(3d)\n" +
            "end;\n" +
            "\n" +
            "declare BEvent\n" +
            "  @role( event )\n" +
            "  @timestamp( eventTimestamp )\n" +
            "  @expires(30d)\n" +
            "end;\n" +
            "\n" +
            "rule \"detect AEvent\"\n" +
            "when $e: AEvent()\n" +
            "then\n" +
            "    System.out.println(\"detect AEvent\");\n" +
            "end\n" +
            "\n" +
            "rule \"detect Event\"\n" +
            "when $e: Event()\n" +
            "then\n" +
            "   System.out.println(\"detect Event\");\n" +
            "end\n" +
            "\n" +
            "rule \"detect BEvent\"\n" +
            "when $e: BEvent()\n" +
            "then\n" +
            "    System.out.println(\"detect BEvent\");\n" +
            "end\n" +
            "\n" +
            "rule \"detect a fact\"\n" +
            "when $f: AFact()\n" +
            "then\n" +
            "  System.out.println(\"detect a fact\");\n" +
            "end\n";

    /**
     * Additional test to assert the consequences of Event which remains in WM whereas they should have expired
     */
    @Test
    public void reproducerExtraTest() throws URISyntaxException, IOException {

        KieContainer oldContainer = buildOldContainer(Collections.emptyList());
        oldContainer.getKieBase(); //init old KieBase

        KieModule newKieModule = initNewModule((kfs) -> kfs.write("src/main/resources/fr/sle/rules.drl", reproducerExtraTest_rule.getBytes()));

        //Upgrade
        oldContainer.updateToVersion(newKieModule.getReleaseId());

        //Assert
        KieContainer updatedContainer = oldContainer; //just naming

        KieSession kieSession = updatedContainer.newKieSession();

        long now = System.currentTimeMillis();

        SessionPseudoClock sessionClock = kieSession.getSessionClock();
        sessionClock.advanceTime(now, TimeUnit.MILLISECONDS);

        AEvent aEvent = new AEvent(new Date(now), "A");
        FactHandle aEventFactHandle = kieSession.insert(aEvent);
        kieSession.fireAllRules();

        //AEvent expires
        sessionClock.advanceTime(Duration.of(4, ChronoUnit.DAYS).toMillis(), TimeUnit.MILLISECONDS);

        kieSession.fireAllRules();
        //A should have expired, but as Event inherited B @expires, it remains in Working Memory
        LOGGER.info("Number of facts in WM: " + kieSession.getFactCount());
        Assert.assertEquals(0, kieSession.getFactCount());
//        Assert.assertEquals(aEventFactHandle, kieSession.getFactHandle(aEvent));


//      A is now expired as Event threshold is crossed
//        sessionClock.advanceTime(Duration.of(27,ChronoUnit.DAYS ).toMillis(), TimeUnit.MILLISECONDS);
//        kieSession.fireAllRules();
//        Assert.assertEquals(0,kieSession.getFactCount());

    }


    //Add a non Event dependent rule named "detect a fact"
    private final String mergeExpirationTest_rule = "package fr.sle;\n" +
            "import fr.sle.AEvent;\n" +
            "import fr.sle.BEvent;\n" +
            "import fr.sle.Event;\n" +
            "import fr.sle.AFact\n" +
            "\n" +
            "declare AEvent\n" +
            "   @role( event )\n" +
            "   @timestamp( eventTimestamp )\n" +
            "   @expires(3d)\n" +
            "end;\n" +
            "\n" +
            "declare BEvent\n" +
            "  @role( event )\n" +
            "  @timestamp( eventTimestamp )\n" +
            "  @expires(30d)\n" +
            "end;\n" +
            "\n" +
            "rule \"detect AEvent\"\n" +
            "when $e: AEvent()\n" +
            "then\n" +
            "    System.out.println(\"detect AEvent\");\n" +
            "end\n" +
            "\n" +
            "rule \"detect Event\"\n" +
            "when $e: Event()\n" +
            "then\n" +
            "   System.out.println(\"detect Event\");\n" +
            "end\n" +
            "\n" +
            "rule \"detect BEvent\"\n" +
            "when $e: BEvent()\n" +
            "then\n" +
            "    System.out.println(\"detect BEvent\");\n" +
            "end\n" +
            "\n" +
            "rule \"detect a fact\"\n" +
            "when $f: AFact()\n" +
            "then\n" +
            "  System.out.println(\"detect a fact\");\n" +
            "end\n" +
            "rule \"detect Event (NEW)\"\n" +
            "when $f: Event()\n" +
            "then\n" +
            "  System.out.println(\"detect Event (NEW)\");\n" +
            "end\n";

    /**
     * This test demonstrates how we are "saved" in the case of {@link ObjectTypeNode#mergeExpirationOffset(ObjectTypeNode)}
     * during the attachNode operation {@link org.drools.core.reteoo.builder.BuildUtils#attachNode(BuildContext, BaseNode)}
     * <p>
     * The modified rules in the kieBase contains a new rule named "detect BEvent" which use a BEvent subclass of Event.
     * This new rule will trigger addRule during network compilation and thus attaching existing OTN (EventB) which leads to merge expirationOffset
     * operation (in favor of -1 value).
     */
    @Test
    public void mergeExpirationTest() throws IOException, URISyntaxException {

        KieContainer oldContainer = buildOldContainer(Collections.emptyList());
        oldContainer.getKieBase(); //init old KieBase

        KieModule newKieModule = initNewModule((kfs) -> kfs.write("src/main/resources/fr/sle/rules.drl", mergeExpirationTest_rule.getBytes()));

        //Upgrade
        oldContainer.updateToVersion(newKieModule.getReleaseId());

        KieContainer updatedContainer = oldContainer; //just naming

        KieSession kieSession = updatedContainer.getKieBase().newKieSession();
        NamedEntryPoint entryPoint = (NamedEntryPoint) kieSession.getEntryPoints().stream().findFirst().get();
        Map<ObjectType, ObjectTypeNode> objectTypeNodes = entryPoint.getEntryPointNode().getObjectTypeNodes();

        //AEvent
        ObjectTypeNode aEventOTN = objectTypeNodes.get(new ClassObjectType(AEvent.class));
        Assert.assertEquals(Duration.of(3, ChronoUnit.DAYS).toMillis() + 1, aEventOTN.getExpirationOffset());

        //BEvent
        ObjectTypeNode bEventOTN = objectTypeNodes.get(new ClassObjectType(BEvent.class));
        Assert.assertEquals(Duration.of(30, ChronoUnit.DAYS).toMillis() + 1, bEventOTN.getExpirationOffset());

        //Event has the good expiration offset
        ObjectTypeNode eventTypeNode = objectTypeNodes.get(new ClassObjectType(Event.class));
        Assert.assertEquals(-1L, eventTypeNode.getExpirationOffset());

    }


    private void attachDebugListener(KieSession kieSession) {

        kieSession.addEventListener(new DebugRuleRuntimeEventListener());
        kieSession.addEventListener(new DebugAgendaEventListener());

    }


    static void logContainer(KieContainer kieContainer) {
        kieContainer.getKieBaseNames().stream().map(kieBase -> {
            LOGGER.info(">> Loading KieBase: " + kieBase);
            return kieBase;
        }).forEach((kieBaseName) -> {
            KieBase kieBase = kieContainer.getKieBase(kieBaseName);
            kieBase.getKiePackages().forEach(kiePackage -> {
                kiePackage.getRules().forEach(ReproducerTest::printRule);
            });

            kieContainer.getKieSessionNamesInKieBase(kieBaseName).forEach((kieSession) -> {
                LOGGER.info("\t >> Containing KieSession: " + kieSession);
            });
        });
    }

    public static void printRule(Rule r) {
        String message = "metadata: " + r.getNamespace() + "\n" + "namespace: " + r.getNamespace() + "\n" + "package name: " + r.getPackageName() + "\n" + "rule: " + r;
        LOGGER.info(message);
    }
}
