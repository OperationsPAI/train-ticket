.PHONY: deploy

deploy:
	# Check if the release 'ts' exists and uninstall it if it does
	@if helm list -n ts | grep -q 'ts'; then \
		echo "Release 'ts' exists. Uninstalling..."; \
		helm uninstall ts -n ts; \
	else \
		echo "Release 'ts' does not exist. Skipping uninstall."; \
	fi
	
	# Install the release with the provided parameters
	echo "Installing 'ts' release..."
	helm install ts manifests/helm/generic_service -n ts --set global.image.tag=637600ea --set services.tsUiDashboard.nodePort=30080