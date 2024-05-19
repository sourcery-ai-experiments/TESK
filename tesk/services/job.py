import logging
import time
from datetime import datetime, timezone

from kubernetes import client
from kubernetes.client.exceptions import ApiException

from tesk.services.exceptions import ServiceStatusCodes
from tesk.services.utils import pprint

logging.basicConfig(format='%(message)s', level=logging.INFO)


class Job:
	def __init__(self, body, name='task-job', namespace='default'):
		self.name = name
		self.namespace = namespace
		self.status = 'Initialized'
		self.bv1 = client.BatchV1Api()
		self.cv1 = client.CoreV1Api()
		self.timeout = 240
		self.body = body
		self.body['metadata']['name'] = self.name

	def run_to_completion(self, poll_interval, check_cancelled, pod_timeout):
		logging.debug(f"Creating job '{self.name}'...")
		logging.debug(pprint(self.body))
		self.timeout = pod_timeout
		try:
			self.bv1.create_namespaced_job(self.namespace, self.body)
		except ApiException as ex:
			if ex.status == ServiceStatusCodes.CONFLICT:
				logging.debug(f'Reading existing job: {self.name} ')
				self.bv1.read_namespaced_job(self.name, self.namespace)
			else:
				logging.debug(ex.body)
				raise ApiException(ex.status, ex.reason) from None
		is_all_pods_running = False
		status, is_all_pods_running = self.get_status(is_all_pods_running)
		while status == 'Running':
			if check_cancelled():
				self.delete()
				return 'Cancelled'
			time.sleep(poll_interval)
			status, is_all_pods_running = self.get_status(is_all_pods_running)
		return status

	def get_status(self, is_all_pods_running):
		job = self.bv1.read_namespaced_job(self.name, self.namespace)
		try:
			if (
				job.status.conditions[0].type == 'Complete'
				and job.status.conditions[0].status
			):
				self.status = 'Complete'
			elif (
				job.status.conditions[0].type == 'Failed'
				and job.status.conditions[0].status
			):
				self.status = 'Failed'
			else:
				self.status = 'Error'
		except (
			TypeError
		):  # The condition is not initialized, so it is not complete yet, wait for it
			self.status = 'Running'
			job_duration = 0
			if job.status.active and job.status.start_time:
				job_duration = (
					datetime.now(timezone.utc) - job.status.start_time
				).total_seconds()
			if job_duration > self.timeout and not is_all_pods_running:
				pods = (
					self.cv1.list_namespaced_pod(
						self.namespace, label_selector=f'job-name={self.name}'
					)
				).items
				is_all_pods_running = True
				for pod in pods:
					if pod.status.phase == 'Pending' and pod.status.start_time:
						is_all_pods_running = False
						delta = (
							datetime.now(timezone.utc) - pod.status.start_time
						).total_seconds()
						if (
							delta > self.timeout
							and pod.status.container_statuses[0].state.waiting.reason
							== 'ImagePullBackOff'
						):
							logging.info(pod.status.container_statuses[0].state.waiting)
							return 'Error', is_all_pods_running

		return self.status, is_all_pods_running

	def delete(self):
		logging.info('Removing failed jobs')
		self.bv1.delete_namespaced_job(
			self.name,
			self.namespace,
			body=client.V1DeleteOptions(propagation_policy='Background'),
		)
