Hi,

we found an issue with the drools 7.x updateToVersion feature which leads to an erroneous set of ExpirationOffset on ObjectTypeNode where event OO Class
hierarchy is involved.

We provide some hints about it, especially in the header of the reproducerMainTest method (l244 of fr.sle.ReproducerTest class):

 * The MAIN test of this reproducer.
 * During the upgrade phase, the whole base is rebuild. But Object Type Node are already in the network as we previously built the kieBase in oldContainer.
 * so {@link org.drools.core.impl.KnowledgeBaseImpl#updateDependentTypes} mess OTN's expirationOffset up.

This issue leads to unwanted facts resident in working memory whereas they should have expired. Furthermore, in production we fall in a case where these facts
remain in working memory and no longer trigger rules (all tuples erased in the FactHandle). Unfortunaly, I haven't reproduce this here, but may Drools Engineer
have an idea of how it could be possible ?

Anyway, fixing wrong expiration offset should be sufficient, as it will allow the fact to expire as they should.

Also, please note that the issue was not present in 6.5.0-Final version. If you want to try it with 6.5.0-Final please follow the instructions at l15 of pom.xml

Steps to reproduce: "mvn clean test"

Results :

Failed tests:   reproducerMainTest(ReproducerTest): expected:<-1> but was:<2592000001>
  reproducerExtraTest(ReproducerTest): expected:<0> but was:<1>

Tests run: 4, Failures: 2, Errors: 0, Skipped: 0


All tests pass with 6.5.0-Final

