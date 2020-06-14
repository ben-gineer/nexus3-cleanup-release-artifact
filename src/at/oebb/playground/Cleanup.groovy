import org.sonatype.nexus.repository.storage.StorageFacet;
import org.sonatype.nexus.common.app.GlobalComponentLookupHelper
import org.sonatype.nexus.repository.maintenance.MaintenanceService
import org.sonatype.nexus.repository.storage.ComponentMaintenance
import org.sonatype.nexus.repository.storage.Query;
import org.sonatype.nexus.script.plugin.RepositoryApi
import org.sonatype.nexus.script.plugin.internal.provisioning.RepositoryApiImpl
import com.google.common.collect.ImmutableList
import org.joda.time.DateTime;
import org.slf4j.Logger

// ----------------------------------------------------
// delete these rows when this script is added to nexus
RepositoryApiImpl repository = null
Logger log = null
GlobalComponentLookupHelper container = null
// ----------------------------------------------------

def retentionDays = 60
def retentionCount = 20
def repositoryName = 'releases'

// Sample whitelist
def whitelist = ["org.javaee7.sample:javaee7-simple-sample"].toArray()

log.info("## Cleanup script started")
MaintenanceService service = container.lookup("org.sonatype.nexus.repository.maintenance.MaintenanceService")
def repo = repository.repositoryManager.get(repositoryName)
def tx = repo.facet(StorageFacet.class).txSupplier().get()
def components = null
try {
    tx.begin()
    components = tx.browseComponents(tx.findBucket(repo))
} catch (Exception e) {
    log.info("Error: " + e)
} finally {
    if (tx != null)
        tx.close()
}

if (components != null) {
    def retentionDate = DateTime.now().minusDays(retentionDays).dayOfMonth().roundFloorCopy()
    int deletedCount = 0
    int compCount = 0
    def listOfComponents = ImmutableList.copyOf(components)
    def previousComp = listOfComponents.head().group() + listOfComponents.head().name()
    try {
        listOfComponents.reverseEach { comp ->
            log.info("> Processing: ${comp.group()}:${comp.name()}:${comp.version()}")
            if (!whitelist.contains(comp.group() + ":" + comp.name())) {
                log.info("- previousComp: ${previousComp}")
                if (previousComp == comp.group() + comp.name()) {
                    compCount++
                    log.info("- compCount: ${compCount}, retentionCount: ${retentionCount}")
                    if (compCount > retentionCount) {
                        log.info("- compDate: ${comp.lastUpdated()} retentionDate: ${retentionDate}")
                        if (comp.lastUpdated().isBefore(retentionDate)) {
                            log.info("- compDate after retentionDate: ${comp.lastUpdated()} isAfter ${retentionDate}")
                            log.info("- Deleting ${comp.group()}:${comp.name()}:${comp.version()}")

                            // ------------------------------------------------
                            // uncomment to delete components and their assets
                            // service.deleteComponent(repo, comp);
                            // ------------------------------------------------

                            log.info("- Deleted")
                            deletedCount++
                        }
                    }
                } else {
                    compCount = 1
                    previousComp = comp.group() + comp.name()
                }
            } else {
                log.info("- Component skipped: ${comp.group()}:${comp.name()}")
            }
            if (deletedCount > 100) {
                throw new Exception("Exiting")
            }
        }
    } catch (Exception e) { }

    log.info("> Deleted components: ${deletedCount}")
}
