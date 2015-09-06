package sagex.tools.gradle.plugins.sagetv7

import org.gradle.api.Plugin
import org.gradle.api.Project
import groovy.xml.MarkupBuilder
import sagex.tools.gradle.plugins.sagetv7.exceptions.InvalidManifestException
import groovyx.net.http.HTTPBuilder
import groovyx.net.http.Method
import groovyx.net.http.ContentType

/**
 * Make life easier for the SageTV v7 plugin developer
 * <p>
 * This is a poor dsl, but is a hold over until a v9 setup is completed.  It's
 * "poor" because it relies too heavily on the dev providing all the input required
 * to do everything.  But it has to be that way because of the free for all setup 
 * that is the Sage v7 plugin repo.  However, it is good enough to allow conversion
 * of plugin dev to gradle.
 * </p>
 * @author db
 *
 */
class Sage7Plugin implements Plugin<Project> {

	abstract static class Sage7Dependency {
		abstract String getType()
		abstract String getValue()
		String minVersion
		String maxVersion
	}

	static class Sage7PluginDependency extends Sage7Dependency {
		final String type = 'Plugin'
		String value 
	}
	
	static class Sage7CoreDependency extends Sage7Dependency {
		final String type = 'Core'
		final String value = ''
	}

	static class Sage7JvmDependency extends Sage7Dependency {
		final String type = 'JVM'
		final String value = ''
	}
	
	static class Sage7Package {
		String type
		URL location
		String md5
		boolean overwrite
	}

	static class Sage7ManifestExtension {
		String name
		String identifier
		String description
		String author
		String created
		String modified
		String version
		boolean isBeta
		String resourcePath
		URL[] webPages
		boolean isServerOnly
		boolean isDesktopRequired
		Sage7Dependency[] dependencies
		String pluginType
		Sage7Package[] packages
		String implementationClass
		String releaseNotes
		URL[] screenshots
		URL[] demoVideos
		String[] os
		String[] stvImports
	}

	static class Sage7PluginDetailsExtension {
		String name
		String email
		String username
		String type
	}
	
	@Override
	void apply(Project proj) {
		proj.extensions.create('sage7Manifest', Sage7ManifestExtension)
		proj.extensions.create('sage7PluginDetails', Sage7PluginDetailsExtension)
		
		proj.task('manifest') {
			inputs.files { proj.configurations.sagePkg }
			outputs.file new File(proj.buildDir, 'sage7-manifest/plugin.xml')
		}
		
		proj.manifest << {
			setDefaults(proj, proj.sage7Manifest)
			validate(proj.sage7Manifest)
			mkManifest(proj, proj.sage7Manifest, it.outputs.files.singleFile)
		}
		
		proj.task('submit') {
			inputs.files proj.manifest
			doLast {
				submit(proj.sage7Manifest, proj.sage7PluginDetails, it.inputs.files.singleFile)
			}
		}
	}
	
	private void submit(Sage7ManifestExtension manifest, Sage7PluginDetailsExtension plugin, File f) {
		println 'Waiting for package files to become available...'
		def xml = new XmlSlurper().parse(f)
		xml.Package.each {
			print "\tChecking: $it.Location"
			def ready = false
			def start = System.currentTimeMillis()
			while(!ready && (System.currentTimeMillis() - start) < 60000) {
				def pkgHttp = new HTTPBuilder(it.Location)
				pkgHttp.request(Method.HEAD, ContentType.TEXT) { req ->
					response.success = { resp ->
						ready = true
					}
					response.failure = { resp ->
						print '.'
						sleep 5000
					}
				}
			}
			if(!ready)
				throw new RuntimeException("Package not available: $it.location")
			else
				println '. [PASS]'
		}
		println 'All packages found!'
		def http = new HTTPBuilder('http://download.sage.tv')
		def body = [
			Name: plugin.name,
			Email: plugin.email,
			Username: plugin.username,
			PluginID: manifest.identifier,
			RequestType: plugin.type,
			Manifest: f.text, 
		]
		http.post(path: '/pluginSubmit.php', body: body)
	}
	
	private void mkManifest(Project proj, Sage7ManifestExtension input, File f) {
		f.delete()
		f.parentFile.mkdirs()
		f.withWriter {
			def ip = new IndentPrinter(it)
			def xml = new MarkupBuilder(ip)
			xml.SageTVPlugin {
				Name(input.name)
				Identifier(input.identifier)
				def attrs = input.isBeta ? [beta: 'true'] : [:]
				Version(attrs, input.version)
				Author(input.author)
				CreationDate(input.created)
				ModificationDate(input.modified)
				if(input.isServerOnly)
					ServerOnly('true')
				if(input.isDesktopRequired)
					Desktop('true')
				input.os.each {
					OS(it)
				}
				PluginType(input.pluginType)
				if(input.implementationClass)
					ImplementationClass(input.implementationClass)
				if(input.resourcePath)
					ResourcePath(input.resourcePath)
				input.dependencies.each { dep ->
					Dependency {
						if(dep.value)
							"$dep.type"(dep.value)
						else
							"$dep.type"()
						if(dep.minVersion)
							MinVersion(dep.minVersion)
						if(dep.maxVersion)
							MaxVersion(dep.maxVersion)						
					}
				}
				input.packages.each { pkg ->
					Package {
						PackageType(pkg.type)
						Location(pkg.location)
						MD5(pkg.md5)
						if(pkg.overwrite)
							Overwrite(true)
					}
				}
				input.stvImports.each {
					STVImport(it)
				}
				Description {
					mkp.yieldUnescaped "<![CDATA[\n$input.description\n"
					ip.printIndent()
					mkp.yieldUnescaped ']]>'
				}
				input.webPages.each {
					Webpage(it)
				}
				input.screenshots.each {
					Screenshot(it)
				}
				input.demoVideos.each {
					DemoVideo(it)
				}
				if(input.releaseNotes) {
					ReleaseNotes {
						mkp.yieldUnescaped "<![CDATA[\n$input.releaseNotes\n"
						ip.printIndent()
						mkp.yieldUnescaped ']]>'
					}
				}
			}
		}
	}
	
	private void validate(Sage7ManifestExtension input) {
		def missing = []
		def invalid = []
		if(!input.name)
			missing << 'name'
		if(!input.identifier)
			missing << 'identifier'
		if(!input.version)
			missing << 'version'
		if(!input.description)
			missing << 'description'
		if(!input.author)
			missing << 'author'
		if(!input.pluginType)
			missing << 'pluginType'
		if(missing)
			throw new InvalidManifestException("Your sage7Manifest is missing required values: $missing")
			
		if(input.identifier.startsWith('-') || !(input.identifier ==~ /[-a-zA-Z0-9]+/))
			invalid << 'identifier'
		if(!(input.version ==~ /\d+(?:\.\d+)*/))
			invalid << 'version'
		if(!(input.pluginType ==~ /Standard|STVI?|Theme|Images|Library/))
			invalid << 'pluginType'
		if(input.created && !(input.created ==~ /\d{4}[.-]\d{2}[.-]\d{2}/))
			invalid << 'created'
		if(input.modified && !(input.modified ==~ /\d{4}[.-]\d{2}[.-]\d{2}/))
			invalid << 'modified'
		if(input.isDesktopRequired && input.isServerOnly)
			invalid << 'isDektopOnly & isServerOnly both can\'t be null'
		if(input.os && !(input.os ==~ /Windows|Linux|Macintosh/))
			invalid << 'os'
		if(invalid)
			throw new InvalidManifestException("Your sage7Manifest contains invalid values: $invalid")
	}
	
	private void setDefaults(Project proj, Sage7ManifestExtension input) {
		input.identifier = input.identifier ?: proj.name
		input.description = input.description ?: proj.description
		input.modified = input.modified ?: new Date().format('yyyy.MM.dd')
	}
}
