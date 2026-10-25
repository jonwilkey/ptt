import org.gradle.api.artifacts.CacheableRule
import org.gradle.api.artifacts.ComponentMetadataContext
import org.gradle.api.artifacts.ComponentMetadataRule

/**
 * Puts every `org.bouncycastle:*-jdk18on` artifact into one virtual platform, so Gradle resolves all of
 * them to the highest version any of them requests (after [DependencyFloors] has raised each to at
 * least its floor). Mixing versions breaks AGP's debug-keystore generation.
 */
@CacheableRule
abstract class BouncyCastleAlignmentRule : ComponentMetadataRule {
    override fun execute(context: ComponentMetadataContext) {
        val id = context.details.id
        if (DependencyFloors.isBouncyCastle(id.group, id.name)) {
            context.details.belongsTo("org.bouncycastle:bc-jdk18on-platform:${id.version}")
        }
    }
}
