import com.cloudbees.hudson.plugins.folder.Folder
import jenkins.model.Jenkins
import org.jenkinsci.plugins.workflow.job.WorkflowJob
import org.jenkinsci.plugins.workflow.job.WorkflowRun
import org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject
import groovy.json.JsonSlurper

/**
  * library for configuring the kubernetes plugin
  *
  * @param project, optional folder name where configs are looked up. Will be determined automatically
  * @param agent, key for lookup an actual agent from the mapping table. 
  *      benefit of using a lookup the jenkins-maintainer can update the image-name. defaults to base
  * @param inheritFrom, directly adressing the inheritFrom, else it will be filled by agent-logic
  * @param containers, list of container-templates of this library. template must exists in the library
  * @param spec, list of spec-templates of this library. template must exists in the library
  * @param showRawYaml, display pod-yaml
  * @param debug, display resulting config-array
  * <pre>
  * {@code
  *  agent {
  *    kubernetes(k8sagent(project:'tut', agent:'base', inheritFrom:'rocky-9-jdk21', containers:'tools cypress:13.0.0', spec:'mini nodeSelector:ba-worker05' , debug:'true'))
  *  }
  * }
  * </pre>
  */


def call(Map opts = [:]) {
    println("k8sagent ==> configuring kubernetes build-pod using groovy ${GroovySystem.version}")

    // Retrieve job information
    String projectFolder = env.JOB_NAME?.split('/')[0]
    String project = opts.get('project', projectFolder ?: 'default-project')
    String agent = opts.get('agent', 'base')
    String showRawYaml = opts.get('showRawYaml', false)

    println("k8sagent ==> using project: ${project} from ${env.JOB_NAME}")

    // inheritFrom with higher priority
    if (opts?.inheritFrom) {
        println("k8sagent ==> using inheritFrom with highest priority: ${opts?.inheritFrom}")
        agent = opts?.inheritFrom
    } else {
        agent = getAgentName(agent)
    }

    String cloud = opts.get('cloud', getCloudForProject(project))
    env['cloud'] = cloud // Store cloud setting in environment

    def result = [
        'cloud'           : cloud,
        'inheritFrom'     : agent,
        'defaultContainer' : 'jnlp',
        'showRawYaml'     : showRawYaml
    ]

    def spec = ZamlParser.parse("spec:")
    spec = mergeContainers(spec, opts.get('containers', ''))
    spec = mergeSpec(spec, opts.get('spec', ''))

    String additonalYAML = ZamlParser.toYaml(spec)
    if (additonalYAML.trim().length() > "spec:".length()) {
        result['yaml'] = additonalYAML.trim()
    }

    // Output debug information if requested
    if (opts.get('debug', false)) {
        result.each { k, v ->
            // console removes idention
            println "k8sagent (debug result-map) ==> ${k}:${k == 'yaml' ? v.bytes.encodeBase64() : v}"
        }
    }
    return result
}

/* 
   Merge container definitions into the spec
   containers:'tools cypress:13.17.0'
*/   
def mergeContainers(spec, containers) {
    if (!containers) return spec

    containers.trim().split('\\s+').each { element ->
        def (key, tag) = element.trim().tokenize(':')
        String template = libraryResource "container/${key}.yaml"
        def map = [__TAG__: tag ?: 'latest']
        def containerSpec = ZamlParser.parse(template, map)
        spec = ZamlParser.merge(spec, containerSpec)
    }

    return spec
}

/* 
   Merge nodeSelector or other spec definitions into the spec
   spec:'nodeSelector:ba-worker05'
*/
def mergeSpec(spec, specLine) {
    if (!specLine) return spec

    specLine.trim().split('\\s+').each { element ->
        def (key, value) = element.trim().tokenize(':')
        String template = libraryResource "spec/${key}.yaml"
        def map = [__VALUE__: value ?: '']
        def specConfig = ZamlParser.parse(template, map)
        spec = ZamlParser.merge(spec, specConfig)
    }

    return spec
}

/**
 * get AgentName by key from agents.json
 */
// Retrieve agent name from agents.json and throw an error if the mapping is not possible
def getAgentName(String key) {
    def agents = libraryResource 'agents.json'
    def agentConfig = new groovy.json.JsonSlurper().parseText(agents)

    // Check if the key exists in the agent configuration
    def agent = agentConfig[key]?.agent

    if (!agent) {
        throw new Exception("Agent mapping not found for key: $key in agents.json")
    }

    println("k8sagent ==> mapping agent ${key} to ${agent.toString()}")

    return agent.toString()
}

/** 
  * Reads active clouds from Environment 
  * example: ba-atu=true ; ba2-atu=false 
  */
def getActiveClouds() {
    def activeClouds = []
    def allClouds = new StringBuffer()
    def clouds=Jenkins.get().clouds.getAll(org.csanchez.jenkins.plugins.kubernetes.KubernetesCloud.class)
      .findAll()
     
      clouds.each { 
        cloud ->   
        def (name,state) = cloud.name.trim().split(":", 2).collect { it.trim() }
        allClouds.append("$name is ${state ?: 'active'}, ")
        if (!state || !state.equals('disabled')) {
            activeClouds << name
        }
    }

    if (!activeClouds) throw new Exception("k8sagent ==> No active cloud found (no cloud or string disabled found)")

    println("k8sagent ==> Active clouds: ${activeClouds.join(', ')} from all clouds ${allClouds.toString()}")
    return activeClouds
}

/**
 * Get cloud for User-Project
 */
def getCloudForProject(project) {
    Set<String> activeClouds = getActiveClouds()

    // in the root-folder of the project there should be a file called jenkins-cloud
    // example-content: cloud=ba-atu  
    def projectProperties = new Properties()
    Jenkins.get().getItems(com.cloudbees.hudson.plugins.folder.Folder.class).findAll {
        it.parent.getClass() != Folder.class
    }.findAll {
        it.getName() == project
    }.each {
        it.getProperties().get(org.jenkinsci.plugins.configfiles.folder.FolderConfigFileProperty.class)
            .getConfigs().findAll {
            it.id == 'jenkins-cloud'
        }.each {
            e -> projectProperties.load(new StringReader(e.content))
        }
    }

    String preferredCloud = projectProperties['cloud']
    if (preferredCloud && activeClouds.contains(preferredCloud)) {
        println("k8sagent ==> found and using project-specific cloud: $preferredCloud")
        return preferredCloud
    }

    if (activeClouds) {
        println("k8sagent ==> using fallback cloud as ${preferredCloud ? 'project setting cloud not in active':'no project setting set'} configured: ${activeClouds[0]}")
        return activeClouds[0]
    }
    // no active cloud
    throw new Exception("No active cloud found for project ${project}, prefered ${preferredCloud} -> ${activeClouds.join(", ")}")
}

// ZAML-Parsing
// https://github.com/mschwehl/zaml

class ZamlParser {
    static class YamlNode {

        boolean listNode = false
        String key
        def value // Can be String, Map, List, or YamlNode

        boolean isNode() {
            return value instanceof YamlNode
        }

        boolean isList() {
            return value instanceof List
        }

        boolean isMap() {
            return value instanceof Map
        }

        String toString() {
            return "YamlNode(key: $key, value: $value)"
        }

        void addElement(String key, Object value) {
            if (isNode()) {
                ((YamlNode) this.value).addElement(key, value)
            }
            if (isMap()) {
                ((Map) this.value).putAt(key, value)
            }
            if (isList()) {
                ((Map) ((List) this.value).getLast()).put(key, value)
            }
        }
    }

    /**
     * Parse a YAML string into a YamlNode object.
     * Do substitutions if needed
     * @param yaml
     * @param substitutions
     * @return
     */
    static YamlNode parse(String yaml, Map substitutions = [:]) {

        // ignore empty lines and comments
        def lines = yaml.readLines().findAll { !(it.trim().isEmpty()) }.collect { it }
        def rootNode = new YamlNode(key: "root", value: [:])
        def stack = [rootNode]

        while (!lines.isEmpty()) {

            def unformattedLine = lines.remove(0)
            // no tabs
            assert !unformattedLine.contains('\t') : unformattedLine

            // ident even
            int currentIndent = countIndent(unformattedLine)
            assert currentIndent % 2 == 0 : unformattedLine

            def line = unformattedLine.trim()
            // go to correct position
            while ((stack.size() * 2) > currentIndent + 2) {
                stack.removeLast()
            }

            def topNode = stack[-1]

            def (key, value) = line.split(":", 2).collect { it.trim() }

            if (key.trim().startsWith("-")) {
                def (listKey, listVal) = line.substring(1).trim().split(":", 2).collect { it.trim() }
                def map = [:]
                map.put(listKey, listVal)
                topNode.value.add(map)
                continue
            }

            if (value) {
                assert !value.trim().startsWith("#") && !value.trim().startsWith("&") : "comment or acnchor after colon"
                String template = parseValue(value)

                // substitute
                substitutions.each { a, b ->
                    template = template.replace(a, b)
                }

                topNode.addElement(key, template)
                continue
            }

            def nextLine = lines.size() > 0 ? lines.first() : null

            // staring a list
            if (nextLine && nextLine.trim().startsWith("-")) {
                def node = new YamlNode(key: key, value: [])
                topNode.addElement(key, node)
                stack << node
            } else {
                def node = new YamlNode(key: key, value: [:])
                topNode.addElement(key, node)
                stack << node
            }

        }

        return rootNode
    }

    private static int countIndent(String line) {
        int intermediate = line.takeWhile { it == ' ' }.length()
        if (line.trim().startsWith("-")) {
            intermediate += 2
        }
        return intermediate
    }

    private static def parseValue(String value) {
        return value != null ? value : ""
    }

    private static def toValue(Object value) {
        return value != null ? value : ""
    }

    static String toYaml(YamlNode node, int level = 0) {
        StringBuilder yaml = new StringBuilder()

        String indent = "  " * level

        if (node.isMap()) {
            node.value.eachWithIndex { k, v, index ->
                if (node.listNode && index == 0) {
                    yaml.append(" ${k}${v != null ? ":" : ""}")
                } else {
                    yaml.append("${indent}${k}${v != null ? ":" : ""}")
                }

                if (v instanceof YamlNode) {
                    yaml.append("\n").append(toYaml(v, level + 1))
                } else if (v instanceof Map) {
                    yaml.append("\n").append(toYaml(new YamlNode(value: v), level + 1))
                } else {
                    yaml.append(" ${toValue(v)}\n")
                }
            }
        } else if (node.isList()) {
            node.value.each { item ->
                def listIdent = "  " * (level - 1)
                yaml.append("${listIdent}-")
                if (item instanceof Map) {
                    def listNode = new YamlNode(value: item, listNode: true)
                    yaml.append(toYaml(listNode, level))
                } else {
                    yaml.append(" ${item}\n")
                }
            }
        }

        return yaml.toString()
    }

    static YamlNode merge(YamlNode base, YamlNode overlay) {
        def mergedNode = new YamlNode(key: base.key, value: base.value instanceof List ? [] : [:])

        if (base.isMap() && overlay.isMap()) {
            // Merge both maps without overriding base values
            base.value.each { k, v ->
                def overlayValue = overlay.value[k]
                if (overlayValue != null) {
                    if (v instanceof YamlNode && overlayValue instanceof YamlNode) {
                        // Recursively merge nested YamlNodes, appending overlay values
                        mergedNode.value[k] = merge(v, overlayValue)
                    } else if (v instanceof List && overlayValue instanceof List) {
                        // Merge lists by appending overlay values
                        mergedNode.value[k] = appendLists(v, overlayValue)
                    } else if (v instanceof Map && overlayValue instanceof Map) {
                        // Merge maps by appending overlay values
                        mergedNode.value[k] = appendMaps(v, overlayValue)
                    } else {
                        // Append overlay value to base value (if it's a simple value type)
                        mergedNode.value[k] = [v, overlayValue].flatten()
                    }
                } else {
                    // If the key doesn't exist in overlay, append the base value
                    mergedNode.value[k] = v
                }
            }

            // Add any new keys from overlay that are not in base
            overlay.value.each { k, v ->
                if (!base.value.containsKey(k)) {
                    mergedNode.value[k] = v
                }
            }
        } else if (base.isList() && overlay.isList()) {
            // Merge lists, preserving the unique elements and matching by 'name'
            mergedNode.value = appendLists(base.value, overlay.value)
        } else {
            // For non-map, non-list types, append overlay value to base value
            mergedNode.value = base.value + overlay.value
        }

        return mergedNode
    }

    // Helper method to append lists while ensuring no duplicates based on 'name'
    private static List appendLists(List baseList, List overlayList) {
        def mergedList = []
        def baseNames = baseList.findAll { it instanceof Map }.collect { it["name"] }

        // First, add all base items
        mergedList.addAll(baseList)

        // Now add overlay items, avoiding duplicates based on 'name'
        overlayList.each { overlayItem ->
            if (overlayItem instanceof Map && overlayItem.containsKey("name")) {
                def match = baseList.find { it instanceof Map && it["name"] == overlayItem["name"] }
                if (match) {
                    // Merge matching items
                    match.putAll(overlayItem)
                } else {
                    // Append new unique item
                    mergedList.add(overlayItem)
                }
            } else {
                // Append item that isn't a map or doesn't contain 'name'
                mergedList.add(overlayItem)
            }
        }

        return mergedList
    }
}
