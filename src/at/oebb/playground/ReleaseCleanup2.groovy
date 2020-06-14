import org.sonatype.nexus.repository.storage.StorageFacet;
import org.sonatype.nexus.repository.storage.Query;
import org.joda.time.DateTime;
import org.joda.time.format.DateTimeFormat;

// See https://gist.github.com/legege/1f8e7b82675fc15beae4a6d9a8640d03 for alternative

// Cleanup parameters
def dryRun = true
def retentionMonths = 3
def keepVersions = 10
def maxItemsToProcess = 2000
// GroupIDs to cleanup
def groups = ['com.sdl.audiencemanager', 'com.sdl.delivery']

def fmt = DateTimeFormat.forPattern('yyyy-MM-dd HH:mm:ss')
['releases'].each { repositoryName ->
    // Get a repository
    def repo = repository.repositoryManager.get(repositoryName)
    // Get a database transaction
    def tx = repo.facet(StorageFacet).txSupplier().get()

    // Search assets that haven't been downloaded for more than a certain number of months, keeping a number of versions
    // Note that an asset is the individual artifact (e.g a pom, or JAR etc), whereas a component is the logical name
    // for the collection of assets with the same coordinates
    try {
        // Begin the transaction
        log.info(">> Opening transaction")
        tx.begin()

        def deletedAssets = 0

        // Only process old assets
        tx.findAssets(Query.builder()
                .where('last_downloaded').isNull()
                .or('last_downloaded <').param(DateTime.now().minusMonths(retentionMonths).toString(fmt))
                .suffix("limit ${maxItemsToProcess}")
                .build(), [repo]).each { asset ->

            if (asset.componentId() != null) {

                def component = tx.findComponent(asset.componentId())
                if (component != null && groups.contains(component.group())) {
                    log.debug("> ${asset.name()} - ${asset.lastDownloaded()}")

                    // Split build from version
                    def (version, build) = component.version().tokenize('-')
                    if (build?.isInteger() && build.toInteger() > 0) {

                        // This simpler version query won't work
                        //def count = tx.countComponents(Query.builder()
                        // .where('group').eq(component.group())
                        //        .and('name').eq(component.name())
                        //       .and('version >').param(component.version())
                        //       .build(), [repo]);

                        def count = 0

                        // Find newer versions of the same component
                        // Note this relies on 'x.y.z-build' format, e.g. '11.5.0-1234'
                        tx.findComponents(Query.builder()
                                .where('group').eq(component.group())
                                .and('name').eq(component.name())
                                .and("version.subString(0, version.indexOf('-'))").eq(version)
                        //.and("version.subString(version.indexOf('-') + 1) >").param(build)
                        //.suffix('order by last_updated desc')
                                .build(), [repo]).each { comp ->
                            def (compVersion, compBuild) = comp.version().tokenize('-')
                            if (compBuild?.isInteger() && compBuild.toInteger() > build.toInteger()) {
                                log.debug("Found newer version ${comp.version()}")
                                count++
                            }
                        }

                        if (count > keepVersions) {
                            log.info("Deleting ${asset.name()} and its component as it hasn't been downloaded for more than ${retentionMonths} months and has ${count - keepVersions} newer versions")
                            if (!dryRun) {
                                tx.deleteAsset(asset)
                                tx.deleteComponent(component)
                                deletedAssets++
                            }
                        }
                    }
                }
                // Don't uncomment this, as it will delete single version assets that we may wish to keep
                //else {
                //log.info("- Deleting asset ${asset.name()} as it wasn't downloaded for more than ${retentionMonths} months")
                // tx.deleteAsset(asset)
                //}
            }
        }

        log.info(">> Deleted ${deletedAssets} assets")
        log.info(">> Committing deletes")
        tx.commit()
    } catch (all) {
        log.error("Exception: ${all}")
        all.printStackTrace()
        log.info(">> Rolling back changes")
        tx.rollback()
    } finally {
        log.info(">> Closing transaction")
        tx.close()
    }
}
