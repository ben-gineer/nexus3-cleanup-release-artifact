import org.sonatype.nexus.repository.storage.StorageFacet;
import org.sonatype.nexus.repository.storage.Query;
import org.joda.time.DateTime;
import org.joda.time.format.DateTimeFormat;

// Cleanup parameters
def retentionMonths = 2
def keepVersions = 5
def itemsToProcess = 100

def fmt = DateTimeFormat.forPattern('yyyy-MM-dd HH:mm:ss');
['releases'].each { repositoryName ->
    // Get a repository
    def repo = repository.repositoryManager.get(repositoryName);
    // Get a database transaction
    def tx = repo.facet(StorageFacet).txSupplier().get();

    // Search assets that haven't been downloaded for more than a certain number of months
    try {
        // Begin the transaction
        log.info("Transaction open")
        tx.begin();
        log.info("Transaction open")
        tx.findAssets(Query.builder()
                .where('last_downloaded <')
                .param(DateTime.now().minusMonths(retentionMonths).toString(fmt))
                .build(), [repo]).take(itemsToProcess).each { asset ->
            if (asset.componentId() != null) {
                def component = tx.findComponent(asset.componentId());
                if (component != null) {
                    // Split build from version
                    def (version, build) = component.version().tokenize('-')
                    log.info("> ${component.name()}:${component.version()} ${version} - ${build}")

                    // Check if there are newer versions of the component with the same name
                    def count = tx.countComponents(Query.builder()
                            .where('name').eq(component.name())
                            .and('version >').param(component.version())
                            //.and('version MATCHES ').param("'${version}\\-'")
                            .build(), [repo]);
                    if (count > keepVersions) {
                        log.info("Deleting asset ${asset.name()} and its component as it hasn't been downloaded for more than ${retentionMonths} months and has ${count - keepVersions} newer versions")
                        // tx.deleteAsset(asset);
                        // tx.deleteComponent(component);
                    }
                } else {
                    log.info("Deleting asset ${asset.name()} as it wasn't downloaded for more than ${retentionMonths} months")
                    // tx.deleteAsset(asset);
                }
            }
        }

        // End the transaction
        log.info("Committing deletes...")
        //tx.commit();
        log.info("Committed deletes")
    } catch (all) {
        log.info("Exception: ${all}")
        all.printStackTrace()
        log.info("Rolling back changes...")
        tx.rollback()
        log.info("Rolled back")
    } finally {
        tx.close();
        log.info("Transaction closed")
    }
}
