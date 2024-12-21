/**
 * Builds an Ansible string according to specified params
 *
 * @param kwargs Map containing build params
 * @param kwargs.buildVars Map containing vars that will be parsed as extra vars
 * @param kwargs.envVars List of additional environment vars
 * @param kwargs.extra_inventory Name or List of dynamic inventory config file
 * @param kwargs.datacenter Four character datacenter identifier
 * @param kwargs.playbookName Name of the playbook to execute (without extension)
 * @param kwargs.groupTarget Name of the group(s) or host(s) to execute against
 */

import groovy.json.JsonOutput
import groovy.json.JsonSlurperClassic

def call(Map kwargs) {
    def automation_base = "/opt/ws1cs/automation"
    def inventory_base = "/opt/ws1cs/inventory"
    def inventory_dynamic = "${inventory_base}/dynamic"
    def inventory_business = "${inventory_base}/${WS1CS_BUSINESS_ENV}"
    def inventory_full = "${inventory_base}/${WS1CS_BUSINESS_ENV}/${WS1CS_DEPLOYMENT_ENV}"

    def test_mode = false
    def extra_inventory = ''
    def datacenter_inventory = ''
    def build_vars = [:]

    def playbook_labels = [
        "build-id-${env.BUILD_ID}",
        "build-number-${env.BUILD_NUMBER}",
        "job-name-${env.JOB_NAME}",
        "node-${env.NODE_NAME}",
        "playbook-${kwargs.playbookName}",
        "group-${kwargs.groupTarget}",
    ]
    def datacenter_id = "${kwargs.datacenter.toLowerCase()}"
    def dc_path = ''

    // List containing dataceneters that use the ansible platform provider
    def dc_ansible_platform_enabled = []

    // Logic to determine platform provider for a given datacenter
    if(dc_ansible_platform_enabled.contains(datacenter_id)) {
        platform_provider = 'ansible'
    } else {
        platform_provider = 'jenkins'
    }

    println("[DEBUG] Platform Provider: ${platform_provider}")

    // Parse: Datacenter
    if(kwargs.containsKey('datacenter') && kwargs.datacenter != 'none') {
        dc_path = "${inventory_full}/${kwargs.datacenter.toLowerCase()}"
        datacenter_inventory = "-i ${dc_path}"
        playbook_labels += "datacenter-${kwargs.datacenter.toLowerCase()}"
        println("[DEBUG] Datacenter Path: ${dc_path}")
    }

    if (platform_provider == 'ansible') {
        // Parse: Extra/Dynamic Inventory
        if(kwargs.containsKey('extra_inventory')) {
            def extraInventoryType = (kwargs.extra_inventory).getClass().toString()
            def extra_inventories = extraInventoryType.contains('List') ? kwargs.extra_inventory : [kwargs.extra_inventory]
            def extra_inventory_list = []
            for (extra_inv in extra_inventories) {
                    extra_inventory_list += "-i ${inventory_dynamic}/${extra_inv}"
                    playbook_labels += "dynamic-inventory-${extra_inv}"z
            }
            extra_inventory = extra_inventory_list.join(' ')
        }
    }

    if (platform_provider == 'jenkins') {
        // Attempt to get 'platform' from SaaSWatch
        def current_platform = false
        if (!("platform::ignore" in kwargs.extra_inventory)) {
            saasWatch.token()
            if (env.ws1uem_env_name) {
                current_platform = saasWatch.getEnvPlatform(env.ws1uem_env_name, "uem")
                println("[DEBUG] Discovered platform for UEM env ${env.ws1uem_env_name}: ${current_platform}")
            } else if (env.cluster_name) {
                def cluster_slug = env.cluster_name.replaceAll(/\./, '')
                current_platform = saasWatch.getEnvPlatform(cluster_slug, "svc")
                println("[DEBUG] Discovered platform for SVC env ${env.cluster_name}: ${current_platform}")
            }
        }
        def platform_overridden = false

        // Parse: Extra/Dynamic Inventory
        if(kwargs.containsKey('extra_inventory')) {
            def extraInventoryType = (kwargs.extra_inventory).getClass().toString()
            def extra_inventories = extraInventoryType.contains('List') ? kwargs.extra_inventory : [kwargs.extra_inventory]
            def extra_inventory_list = []
            for (extra_inv in extra_inventories) {
                // Look for specific prefixes like 'prefix::' and process accordingly
                switch(extra_inv.find(/^\w*::/)) {
                    case 'platform::':
                        // If platform already overridden, fail
                        if (platform_overridden) {
                            throw new Exception("'platform' override must only be provided once")
                        }
                        def platform_inv = extra_inv.replace('platform::', '')
                        println("[DEBUG] Overriding platform to: ${platform_inv}")
                        // Allow 'platform' to be skipped with 'platform::ignore'
                        if (platform_inv != 'ignore') {
                            // Platform dirs are expected under datacenter path
                            extra_inventory_list += "-i ${dc_path}/${platform_inv}"
                            playbook_labels += "platform-inventory-${platform_inv}"
                        }
                        platform_overridden = true
                        break
                    // Others are assumed to be dynamic inventories
                    default:
                        extra_inventory_list += "-i ${inventory_dynamic}/${extra_inv}"
                        playbook_labels += "dynamic-inventory-${extra_inv}"
                }
            }
            extra_inventory = extra_inventory_list.join(' ')
        }

        // If 'platform' was not overridden by extra_inventory, use the environment platform
        if (!platform_overridden && current_platform) {
            datacenter_inventory += " -i ${dc_path}/${current_platform}"
            playbook_labels += "platform-inventory-${current_platform}"
        } else if (!platform_overridden) {
            throw new Exception("'platform' is required either from the current environment or as 'platform::<platform>' in 'extra_inventory'")
        }
    }

    // Parse: Build Vars
    if(kwargs.containsKey('buildVars')) {
        // Allow stringified JSON to be passed
        if (kwargs.buildVars instanceof String) {
            build_vars = new JsonSlurperClassic().parseText(kwargs.buildVars)
        } else {
            build_vars = kwargs.buildVars
        }
    }

    // Parse: Test Mode
    if(kwargs.containsKey('testMode')) {
        test_mode = kwargs.testMode
    }

    println("[DEBUG] Keyword Args:    ${kwargs}")
    println("[DEBUG] Playbook Labels: ${playbook_labels}")

    // Parse Build Vars
    build_vars += [
        'ara_playbook_labels': playbook_labels.join(','),
        'ara_playbook_name': kwargs.playbookName,
        'business_environment': WS1CS_BUSINESS_ENV,
        'deployment_environment': WS1CS_DEPLOYMENT_ENV
    ]

    // Conditionally set 'ws1cs_hosts' only if 'groupTarget' provided
    if(kwargs.containsKey('groupTarget')) {
        build_vars += ['ws1cs_hosts': kwargs.groupTarget]
    }

    // Parse build_vars & Inject to Environment
    def build_json = JsonOutput.toJson(build_vars)

    // Generate Ansible Command
    def ansibleCmd = [
        "ansible-playbook",
        "playbooks/${kwargs.playbookName}.yml",
        "-i ${inventory_base}/global.yml",
        "-i ${inventory_business}/groups.yml",
        "-i ${inventory_full}/groups.yml",
        "${datacenter_inventory}",
        "${extra_inventory}",
        "--extra-vars '${build_json}'"
    ].join(' ')

    // Execute build script with injected vars
    dir(automation_base) {
        if(test_mode) {
            println("Test Mode Enabled")
            println(ansibleCmd)
        } else {
            rc = sh script:ansibleCmd, returnStatus:true
            println("Ansible return code: ${rc}")
            if (rc > 0 && rc != 2) {
                pagerduty.alert("Ansible runtime error occurred.  Check Ansible logs.")
            }
            if (rc) {
                currentBuild.result = 'FAILURE'
            }
            sh "exit ${rc}"
        }
    }
}
